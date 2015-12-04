/*
 * Copyright (C) 2003-2015 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.clouddrive.dropbox;

import com.dropbox.core.DbxAccountInfo;
import com.dropbox.core.DbxAuthFinish;
import com.dropbox.core.DbxClient;
import com.dropbox.core.DbxClient.Downloader;
import com.dropbox.core.DbxDelta;
import com.dropbox.core.DbxDelta.Entry;
import com.dropbox.core.DbxEntry;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxHost;
import com.dropbox.core.DbxPath;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.DbxRequestUtil;
import com.dropbox.core.DbxThumbnailFormat;
import com.dropbox.core.DbxThumbnailSize;
import com.dropbox.core.DbxUrlWithExpiration;
import com.dropbox.core.DbxWriteMode;
import com.dropbox.core.http.HttpRequestor;
import com.dropbox.core.util.Maybe;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.ConflictException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.oauth2.UserToken;
import org.exoplatform.clouddrive.utils.ChunkIterator;
import org.exoplatform.clouddrive.utils.Web;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * All calls to Dropbox Cloud API here.
 * 
 */
public class DropboxAPI {

  public static final String ROOT_URL               = "https://www.dropbox.com/home";

  public static final String ROOT_PATH              = "/";

  public static final int    DELTA_LONGPOLL_TIMEOUT = 60;

  public static final String DELTA_LONGPOLL_URL     = "https://api-notify.dropbox.com/1/longpoll_delta";

  public static final String CONTENT_BASE_URL       = "https://api-content.dropbox.com/1/";

  public static final String CONTENT_PREVIEW_URL    = CONTENT_BASE_URL + "previews/auto";

  public static final String CONTENT_THUMBNAIL_URL  = CONTENT_BASE_URL + "thumbnails/auto";

  protected static final Log LOG                    = ExoLogger.getLogger(DropboxAPI.class);

  /**
   * OAuth2 tokens storage base...
   */
  class StoredToken extends UserToken {

    /**
     * Save OAuth2 token from Dropbox API.
     * 
     * @param accessToken
     * @throws CloudDriveException
     */
    void store(String accessToken) throws CloudDriveException {
      this.store(accessToken, null /* refreshToken */, -1/* expirationTime */);
    }
  }

  /**
   * Dropbox file metadata with iterator over whole set of file children in Dropbox. This iterator hides
   * next-chunk logic on request to the service. <br>
   * Iterator methods can throw {@link CloudDriveException} in case of remote or communication errors.
   */
  class FileMetadata extends ChunkIterator<DbxEntry> {
    final String idPath;

    boolean      changed;

    String       hash;

    /**
     * Target file or folder.
     */
    DbxEntry     target;

    FileMetadata(String idPath, String hash)
        throws TooManyFilesException, NotFoundException, DropboxException, RefreshAccessException {
      this.idPath = idPath;

      this.hash = hash;

      // fetch first, this also will fetch the path item metadata
      this.iter = nextChunk();
    }

    protected Iterator<DbxEntry> nextChunk() throws TooManyFilesException,
                                             NotFoundException,
                                             DropboxException,
                                             RefreshAccessException {
      try {
        DbxEntry.WithChildren metadata;
        if (hash == null) {
          metadata = client.getMetadataWithChildren(idPath);
        } else {
          Maybe<DbxEntry.WithChildren> maybe = client.getMetadataWithChildrenIfChanged(idPath, hash);
          if (maybe.isNothing()) {
            changed = false;
            available(0);
            return Collections.<DbxEntry> emptyList().iterator();
          } else {
            metadata = maybe.getJust();
          }
        }

        if (metadata == null) {
          throw new NotFoundException("No file or folder at this path: " + idPath);
        }

        changed = true;
        hash = metadata.hash;

        // Get items and let progress indicator to know the available amount
        target = metadata.entry;
        List<DbxEntry> children = metadata.children;
        available(children.size());

        return children.iterator();
      } catch (DbxException.InvalidAccessToken e) {
        String msg = "Invalid access credentials";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " (access token) : " + e.getMessage(), e);
        }
        throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
      } catch (DbxException.RetryLater e) {
        String msg = "Dropbox overloaded or hit rate exceeded";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg + ". Please try again later.");
      } catch (DbxException.BadResponseCode e) {
        if (e.statusCode == 406) {
          String msg = "Folder " + idPath + " listings containing more than the specified amount of files";
          if (LOG.isDebugEnabled()) {
            LOG.debug(msg + ": " + e.getMessage(), e);
          }
          throw new TooManyFilesException(msg);
        } else {
          String msg = "Error requesting Metadata service";
          if (LOG.isDebugEnabled()) {
            LOG.debug(msg + ": " + e.getMessage(), e);
          }
          throw new DropboxException(msg);
        }
      } catch (DbxException e) {
        String msg = "Error requesting Metadata service";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      }
    }

    protected boolean hasNextChunk() {
      // FYI Dropbox has no paging for metadata, but has a limit (will be catch in nextChunk() as 406 status)
      return false;
    }
  }

  /**
   * Delta changes iterator of user Dropbox. This iterator hides next-chunk logic on
   * request to the service. <br>
   * Iterator methods can throw {@link DropboxException} in case of remote or communication errors.
   */
  class DeltaChanges extends ChunkIterator<Entry<DbxEntry>> {

    /**
     * Cursor to fetch deltas.
     */
    String  cursor;

    /**
     * Latest delta has_more value.
     */
    boolean hasMore = false;

    /**
     * Reset flag from the last delta response.
     */
    boolean reset   = false;

    DeltaChanges(String cursor) throws DropboxException, RefreshAccessException {
      this.cursor = cursor;

      // fetch first
      this.iter = nextChunk();
    }

    protected Iterator<Entry<DbxEntry>> nextChunk() throws DropboxException, RefreshAccessException {
      try {
        // implement actual logic here
        DbxDelta<DbxEntry> delta = client.getDelta(cursor);

        // remember position for next chunk and next iterators
        cursor = delta.cursor;
        hasMore = delta.hasMore;
        reset = delta.reset;

        return delta.entries.iterator();
      } catch (DbxException.InvalidAccessToken e) {
        String msg = "Invalid access credentials";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " (access token) : " + e.getMessage(), e);
        }
        throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
      } catch (DbxException.RetryLater e) {
        String msg = "Dropbox overloaded or hit rate exceeded";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg + ". Please try again later.");
      } catch (DbxException e) {
        String msg = "Error requesting Delta service";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      }
    }

    /**
     * {@inheritDoc}
     */
    protected boolean hasNextChunk() {
      return hasMore;
    }

    String getCursor() {
      return cursor;
    }

    boolean hasReset() {
      return reset;
    }
  }

  /**
   * Dropbox uses specific encoding of file paths in its URLs, it is almost the same as Java URI does, but
   * also requires encoding of such chars as ampersand etc.<br>
   * Code grabbed from here http://stackoverflow.com/questions/724043/http-url-address-encoding-in-java.
   */
  class PathEncoder {
    String encode(String input) {
      StringBuilder resultStr = new StringBuilder();
      for (char ch : input.toCharArray()) {
        if (isUnsafe(ch)) {
          resultStr.append('%');
          resultStr.append(toHex(ch / 16));
          resultStr.append(toHex(ch % 16));
        } else {
          resultStr.append(ch);
        }
      }
      return resultStr.toString();
    }

    private char toHex(int ch) {
      return (char) (ch < 10 ? '0' + ch : 'A' + ch - 10);
    }

    private boolean isUnsafe(char ch) {
      if (ch > 128 || ch < 0) {
        return true;
      }
      // Do not escape slash!
      return " %$&+,:;=?@<>#%".indexOf(ch) >= 0;
    }
  }

  private DbxClient   client;

  private StoredToken token;

  private PathEncoder pathEncoder = new PathEncoder();

  /**
   * Create Dropbox API from OAuth2 authentication code.
   * 
   * @param config {@link DbxRequestConfig}
   * @param authData {@link DbxAuthFinish} authorization results
   * @throws DropboxException if authorization failed for any reason.
   * @throws CloudDriveException if credentials store exception happen
   */
  DropboxAPI(DbxRequestConfig config, DbxAuthFinish authData) throws DropboxException, CloudDriveException {

    // create Cloud API client using authorization data.
    this.client = new DbxClient(config, authData.accessToken);

    this.token = new StoredToken();
    this.token.store(authData.accessToken);

    // init user (enterprise etc.)
    initUser();
  }

  /**
   * Create Dropbox API from existing user credentials.
   * 
   * @param config {@link DbxRequestConfig}
   * @param accessToken {@link String}
   * @throws CloudDriveException if credentials store exception happen
   */
  DropboxAPI(DbxRequestConfig config, String accessToken) throws CloudDriveException {

    // create Cloud API client and authenticate it using stored token.
    this.client = new DbxClient(config, accessToken);

    this.token = new StoredToken();
    this.token.load(accessToken, null /* refreshToken */, -1 /* expirationTime */);

    // init user (enterprise etc.)
    initUser();
  }

  /**
   * Update OAuth2 token to a new one.
   * 
   * @param newToken {@link StoredToken}
   * @throws CloudDriveException
   */
  void updateToken(UserToken newToken) throws CloudDriveException {
    this.token.merge(newToken);
  }

  /**
   * Current OAuth2 token associated with this API instance.
   * 
   * @return {@link StoredToken}
   */
  StoredToken getToken() {
    return token;
  }

  /**
   * Currently connected cloud user.
   * 
   * @return DbxAccountInfo
   * @throws DropboxException
   * @throws RefreshAccessException
   */
  DbxAccountInfo getCurrentUser() throws DropboxException, RefreshAccessException {
    try {
      return client.getAccountInfo();
    } catch (DbxException e) {
      throw new DropboxException("Error requesting account info: " + e.getMessage(), e);
    }
  }

  FileMetadata getWithChildren(String idPath, String hash) throws TooManyFilesException,
                                                           DropboxException,
                                                           RefreshAccessException {
    try {
      // FYI use of hash could have a sense for full-traversing sync use
      return new FileMetadata(idPath, hash);
    } catch (NotFoundException e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(">> getWithChildren(" + idPath + ", " + hash + "): " + e.getMessage());
      }
      return null;
    }
  }

  DbxEntry get(String idPath) throws DropboxException, NotFoundException, RefreshAccessException {
    try {
      // TODO use DbxClient.getMetadataIfChanged() using stored locally hash for folder
      DbxEntry md = client.getMetadata(idPath);
      return md;
    } catch (DbxException.InvalidAccessToken e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
    } catch (DbxException.RetryLater e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + ". Please try again later.");
    } catch (DbxException.BadResponseCode e) {
      String msg = "Error requesting file Metadata service";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    } catch (DbxException e) {
      String msg = "Error requesting file Metadata service";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    }
  }

  Downloader getContent(String idPath) throws DropboxException, NotFoundException, RefreshAccessException {
    try {
      // DbxEntry md = client.getFile(idPath, null, output);
      Downloader downloader = client.startGetFile(idPath, null);
      return downloader;
    } catch (DbxException.InvalidAccessToken e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
    } catch (DbxException.RetryLater e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + ". Please try again later.");
    } catch (DbxException.BadResponseCode e) {
      String msg = "Error requesting file content";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    } catch (DbxException e) {
      String msg = "Error requesting file content";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    }
  }

  /**
   * Link (URL) to a file for opening by its owner on Dropbox site.
   * 
   * @param parentPath
   * @param name
   * @return String with the file URL.
   */
  String getUserFileLink(String parentPath, String name) {
    // XXX it is undocumented URL structure observed on Dropbox
    // for files https://www.dropbox.com/home?preview=Comminications+in+Hanoi.jpg
    // file in subfolder https://www.dropbox.com/home/test/sub-folder%20N1?preview=20150713_182628.jpg
    StringBuilder link = new StringBuilder(ROOT_URL);
    if (!ROOT_PATH.equals(parentPath)) {
      // FYI prior Nov 12 2015 was: link.append(Web.pathEncode(parentPath));
      link.append(pathEncoder.encode(parentPath));
    }
    link.append("?preview=").append(Web.formEncode(name));
    return link.toString();
  }

  /**
   * Link (URL) to a folder for opening by its owner on Dropbox site.
   * 
   * @param folder {@link String}
   * @return String with the file URL.
   */
  String getUserFolderLink(String path) {
    // for folders https://www.dropbox.com/home/test/sub-folder%20N1
    if (ROOT_PATH.equals(path)) {
      // XXX can smth better be possible?
      return ROOT_URL;
    } else {
      // FYI prior Nov 12 2015 was: return new
      // StringBuilder(ROOT_URL).append(Web.pathEncode(path)).toString();
      return new StringBuilder(ROOT_URL).append(pathEncoder.encode(path)).toString();
    }
  }

  /**
   * Temporary link (URL with expiration in few hours) to a file content for streaming/downloading.
   * 
   * @param dbxPath {@link String}
   * @return DbxUrlWithExpiration with the file embed URL.
   * @throws RefreshAccessException
   * @throws DropboxException
   */
  DbxUrlWithExpiration getDirectLink(String dbxPath) throws RefreshAccessException, DropboxException {
    if (ROOT_PATH.equals(dbxPath)) {
      // no embed link for root
      return null;
    } else {
      try {
        DbxUrlWithExpiration dbxUrl = client.createTemporaryDirectUrl(dbxPath);
        return dbxUrl;
      } catch (DbxException.InvalidAccessToken e) {
        String msg = "Invalid access credentials";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " (access token) : " + e.getMessage(), e);
        }
        throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
      } catch (DbxException.RetryLater e) {
        String msg = "Dropbox overloaded or hit rate exceeded";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg + ". Please try again later.");
      } catch (DbxException.BadResponseCode e) {
        String msg = "Error requesting file link";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      } catch (DbxException e) {
        String msg = "Error requesting file link";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      }
    }
  }

  /**
   * Create shared link to a file or folder' "preview page" on Dropbox. See more on
   * <a href="https://www.dropbox.com/help/167">https://www.dropbox.com/help/167</a><br>
   * 
   * @param dbxPath {@link String}
   * @return DbxUrlWithExpiration with the shared link of file or folder "preview page".
   * @throws RefreshAccessException
   * @throws DropboxException
   */
  DbxUrlWithExpiration getSharedLink(String dbxPath) throws RefreshAccessException, DropboxException {
    if (ROOT_PATH.equals(dbxPath)) {
      // no shared link for root
      return null;
    } else {
      try {
        // FYI this URL has long expiration (years)
        // but Dropbox Java client we don't get expiration time for the link, thus need custom API call
        // String sharedUrl = client.createShareableUrl(dbxPath);

        DbxPath.checkArg("path", dbxPath);

        String apiPath = "1/shares/auto" + dbxPath;
        String[] params = { "short_url", "false" };

        return DbxRequestUtil.doPost(client.getRequestConfig(),
                                     client.getAccessToken(),
                                     DbxHost.Default.api,
                                     apiPath,
                                     params,
                                     null,
                                     new DbxRequestUtil.ResponseHandler<DbxUrlWithExpiration>() {
                                       @Override
                                       public DbxUrlWithExpiration handle(HttpRequestor.Response response) throws DbxException {
                                         if (response.statusCode == 404)
                                           return null;
                                         if (response.statusCode != 200)
                                           throw DbxRequestUtil.unexpectedStatus(response);
                                         DbxUrlWithExpiration uwe = DbxRequestUtil.readJsonFromResponse(DbxUrlWithExpiration.Reader,
                                                                                                        response.body);
                                         return uwe;
                                       }
                                     });
      } catch (DbxException.InvalidAccessToken e) {
        String msg = "Invalid access credentials";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " (access token) : " + e.getMessage(), e);
        }
        throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
      } catch (DbxException.RetryLater e) {
        String msg = "Dropbox overloaded or hit rate exceeded";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg + ". Please try again later.");
      } catch (DbxException.BadResponseCode e) {
        String msg = "Error requesting file's shared link";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      } catch (DbxException e) {
        String msg = "Error requesting file's shared link";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      }
    }
  }

  /**
   * Link (URL) to thumbnail of a file.
   * 
   * @param dbxPath {@link String}
   * @return Downloader with the file thumbnail.
   * @throws DropboxException
   * @throws RefreshAccessException
   */
  Downloader getThumbnail(String dbxPath, DbxThumbnailSize size) throws DropboxException, RefreshAccessException {
    if (ROOT_PATH.equals(dbxPath)) {
      // no embed link for root
      return null;
    } else {
      try {
        DbxThumbnailFormat format = DbxThumbnailFormat.bestForFileName(dbxPath, DbxThumbnailFormat.JPEG);
        Downloader downloader = client.startGetThumbnail(size, format, dbxPath, null);
        return downloader;
      } catch (DbxException.InvalidAccessToken e) {
        String msg = "Invalid access credentials";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " (access token) : " + e.getMessage(), e);
        }
        throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
      } catch (DbxException.RetryLater e) {
        String msg = "Dropbox overloaded or hit rate exceeded";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg + ". Please try again later.");
      } catch (DbxException.BadResponseCode e) {
        String msg = "Error requesting file thumbnail";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      } catch (DbxException e) {
        String msg = "Error requesting file thumbnail";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      }
    }
  }

  DeltaChanges getDeltas(String cursor) throws DropboxException, RefreshAccessException {
    return new DeltaChanges(cursor);
  }

  DbxEntry.File uploadFile(String parentId, String name, InputStream data, String updateRev) throws DropboxException,
                                                                                             NotFoundException,
                                                                                             RefreshAccessException,
                                                                                             ConflictException {
    String path = filePath(parentId, name);
    try {
      DbxWriteMode mode = updateRev != null ? DbxWriteMode.update(updateRev) : DbxWriteMode.add();
      // Uploading file with unknown length (-1)
      DbxEntry.File file = client.uploadFile(path, mode, -1, data);
      return file;
    } catch (DbxException.InvalidAccessToken e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
    } catch (DbxException.RetryLater e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + ". Please try again later.");
    } catch (DbxException.BadResponseCode e) {
      if (e.statusCode == 409) {
        String msg = "File " + path + " already exists";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new ConflictException(msg);
      } else {
        String msg = "Error creating file ";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + path + ": (" + e.statusCode + ") " + e.getMessage(), e);
        }
        throw new DropboxException(msg + name);
      }
    } catch (DbxException e) {
      String msg = "Error creating file ";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + path + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + name);
    } catch (IOException e) {
      String msg = "Error creating file ";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + path + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + name + ". " + e.getMessage());
    }
  }

  DbxEntry.Folder createFolder(String parentId, String name) throws DropboxException,
                                                             NotFoundException,
                                                             RefreshAccessException,
                                                             ConflictException {
    String path = filePath(parentId, name);
    try {
      DbxEntry.Folder folder = client.createFolder(path);
      return folder;
    } catch (DbxException.InvalidAccessToken e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
    } catch (DbxException.RetryLater e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + ". Please try again later.");
    } catch (DbxException.BadResponseCode e) {
      if (e.statusCode == 409) {
        String msg = "Folder " + path + " already exists";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new ConflictException(msg);
      } else {
        String msg = "Error creating folder ";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + path + ": (" + e.statusCode + ") " + e.getMessage(), e);
        }
        throw new DropboxException(msg + name);
      }
    } catch (DbxException e) {
      String msg = "Error creating folder ";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + path + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + name);
    }
  }

  /**
   * Delete a Dropbox file or folder at given path (ID path lower-case).
   * 
   * @param idPath
   * @throws DropboxException
   * @throws NotFoundException
   * @throws TooManyFilesException
   * @throws RefreshAccessException
   */
  void delete(String idPath) throws DropboxException, NotFoundException, TooManyFilesException, RefreshAccessException {
    try {
      client.delete(idPath);
    } catch (DbxException.InvalidAccessToken e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
    } catch (DbxException.RetryLater e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + ". Please try again later.");
    } catch (DbxException.BadResponseCode e) {
      if (e.statusCode == 404) {
        String msg = "File not found " + idPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new NotFoundException(msg);
      } else if (e.statusCode == 406) {
        String msg = "Too many files would be removed at " + idPath + ". Reduce the amount of files and try again";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new TooManyFilesException(msg);
      } else {
        String msg = "Error deleting file ";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + idPath + ": (" + e.statusCode + ") " + e.getMessage(), e);
        }
        throw new DropboxException(msg + idPath);
      }
    } catch (DbxException e) {
      String msg = "Error deleting file " + idPath;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    }
  }

  DbxEntry.File restoreFile(String idPath, String rev) throws DropboxException, NotFoundException, RefreshAccessException {
    try {
      DbxEntry.File file = client.restoreFile(idPath, rev);
      return file;
    } catch (DbxException.InvalidAccessToken e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
    } catch (DbxException.RetryLater e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + ". Please try again later.");
    } catch (DbxException.BadResponseCode e) {
      if (e.statusCode == 404) {
        String msg = "File not found for restoration " + idPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " (rev: " + rev + "): " + e.getMessage(), e);
        }
        throw new NotFoundException(msg);
      } else {
        String msg = "Error restoring file ";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + idPath + " (rev: " + rev + "): (" + e.statusCode + ") " + e.getMessage(), e);
        }
        throw new DropboxException(msg + idPath);
      }
    } catch (DbxException e) {
      String msg = "Error restoring file " + idPath;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (rev: " + rev + "): " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    }
  }

  @Deprecated // NOT USED AND WILL NOT WORK THIS WAY
  DbxEntry.Folder restoreFolder(String idPath, String rev) throws DropboxException,
                                                           NotFoundException,
                                                           RefreshAccessException {
    try {
      // XXX Custom API call here:
      // DbxRequestUtil.doGet(requestConfig, accessToken, host, path, params, headers, handler);
      DbxPath.checkArgNonRoot("path", idPath);
      if (rev == null)
        throw new IllegalArgumentException("'rev' can't be null");
      if (rev.length() == 0)
        throw new IllegalArgumentException("'rev' can't be empty");

      String apiPath = "1/restore/auto" + idPath;
      String[] params = { "rev", rev, };

      // Doesn't work for folders, Dropbox return:
      // error: "Unable to restore directory '/eXo TEST/empty folder'"
      return DbxRequestUtil.doGet(client.getRequestConfig(),
                                  client.getAccessToken(),
                                  DbxHost.Default.api,
                                  apiPath,
                                  params,
                                  null,
                                  new DbxRequestUtil.ResponseHandler<DbxEntry.Folder>() {
                                    public DbxEntry.Folder handle(HttpRequestor.Response response) throws DbxException {
                                      if (response.statusCode == 404)
                                        return null;
                                      if (response.statusCode != 200)
                                        throw DbxRequestUtil.unexpectedStatus(response);
                                      return DbxRequestUtil.readJsonFromResponse(DbxEntry.Folder.Reader, response.body);
                                    }
                                  });
    } catch (DbxException.InvalidAccessToken e) {
      String msg = "Invalid access credentials";
      LOG.warn(msg + " (access token) : " + e.getMessage(), e);
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
    } catch (DbxException.RetryLater e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      LOG.warn(msg + ": " + e.getMessage(), e);
      throw new DropboxException(msg + ". Please try again later.");
    } catch (DbxException.BadResponseCode e) {
      if (e.statusCode == 404) {
        String msg = "Folder not found for restoration " + idPath;
        LOG.error(msg + " (rev: " + rev + "): " + e.getMessage(), e);
        throw new NotFoundException(msg);
      } else {
        String msg = "Error restoring folder ";
        LOG.error(msg + idPath + " (rev: " + rev + "): (" + e.statusCode + ") " + e.getMessage(), e);
        throw new DropboxException(msg + idPath);
      }
    } catch (DbxException e) {
      String msg = "Error restoring folder " + idPath;
      LOG.error(msg + " (rev: " + rev + "): " + e.getMessage(), e);
      throw new DropboxException(msg);
    }
  }

  DbxEntry move(String fromPath, String toPath) throws DropboxException,
                                                TooManyFilesException,
                                                ConflictException,
                                                NotFoundException,
                                                RefreshAccessException {
    try {
      DbxEntry item = client.move(fromPath, toPath);
      return item;
    } catch (DbxException.InvalidAccessToken e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
    } catch (DbxException.RetryLater e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + ". Please try again later.");
    } catch (DbxException.BadResponseCode e) {
      if (e.statusCode == 403) {
        String msg = "Invalid move operation";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ", " + fromPath + " to " + toPath + ": " + e.getMessage(), e);
        }
        throw new ConflictException(msg + ", check if destination don't have such file or folder already");
      } else if (e.statusCode == 404) {
        String msg = "File or folder not found for move " + fromPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " to " + toPath + ": " + e.getMessage(), e);
        }
        throw new NotFoundException(msg);
      } else if (e.statusCode == 406) {
        String msg = "Moving of " + fromPath + " involve more than the allowed amount of files";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new TooManyFilesException(msg);
      } else {
        String msg = "Error moving file";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ", " + fromPath + " to " + toPath + ": (" + e.statusCode + ") " + e.getMessage(), e);
        }
        throw new DropboxException(msg + " " + fromPath);
      }
    } catch (DbxException e) {
      String msg = "Error moving file";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ", " + fromPath + " to " + toPath + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + " " + fromPath);
    }
  }

  /**
   * Copy file to a new one. If file was successfully copied this method return new file object.
   * 
   * @param fromPath
   * @param toPath
   * @return {@link DbxEntry} of actually copied file.
   * @throws DropboxException
   * @throws NotFoundException
   * @throws RefreshAccessException
   * @throws ConflictException
   * @throws TooManyFilesException
   */
  DbxEntry copy(String fromPath, String toPath) throws DropboxException,
                                                NotFoundException,
                                                ConflictException,
                                                RefreshAccessException,
                                                TooManyFilesException {

    try {
      DbxEntry item = client.copy(fromPath, toPath);
      return item;
    } catch (DbxException.InvalidAccessToken e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
    } catch (DbxException.RetryLater e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + ". Please try again later.");
    } catch (DbxException.BadResponseCode e) {
      if (e.statusCode == 403) {
        String msg = "Invalid copy operation";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ", " + fromPath + " to " + toPath + ": " + e.getMessage(), e);
        }
        throw new ConflictException(msg + ", check if destination don't have such file or folder already");
      } else if (e.statusCode == 404) {
        String msg = "File or folder not found for copying " + fromPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " to " + toPath + ": " + e.getMessage(), e);
        }
        throw new NotFoundException(msg);
      } else if (e.statusCode == 406) {
        String msg = "Copying of " + fromPath + " involve more than the allowed amount of files";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new TooManyFilesException(msg);
      } else {
        String msg = "Error copying file";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ", " + fromPath + " to " + toPath + ": (" + e.statusCode + ") " + e.getMessage(), e);
        }
        throw new DropboxException(msg + " " + fromPath);
      }
    } catch (DbxException e) {
      String msg = "Error copying file";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ", " + fromPath + " to " + toPath + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + " " + fromPath);
    }
  }

  // ********* internal *********

  private void initUser() throws DropboxException, RefreshAccessException, NotFoundException {
    // TODO do we have really something to init for the user?
    // DbxAccountInfo user = getCurrentUser();
    // this.userId = user.userId;
    // this.userDisplayName = user.displayName;
  }

  String filePath(String parentId, String title) {
    StringBuilder path = new StringBuilder(parentId);
    if (!ROOT_PATH.equals(parentId)) {
      path.append('/');
    }
    return path.append(title).toString();
  }

}
