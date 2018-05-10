/*
 * Copyright (C) 2003-2018 eXo Platform SAS.
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

import com.dropbox.core.BadResponseCodeException;
import com.dropbox.core.DbxApiException;
import com.dropbox.core.DbxAuthFinish;
import com.dropbox.core.DbxDownloader;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.InvalidAccessTokenException;
import com.dropbox.core.RetryException;
import com.dropbox.core.v1.DbxEntry;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.CommitInfo;
import com.dropbox.core.v2.files.CreateFolderErrorException;
import com.dropbox.core.v2.files.CreateFolderResult;
import com.dropbox.core.v2.files.DeleteErrorException;
import com.dropbox.core.v2.files.DeleteResult;
import com.dropbox.core.v2.files.DownloadErrorException;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.GetMetadataErrorException;
import com.dropbox.core.v2.files.GetTemporaryLinkErrorException;
import com.dropbox.core.v2.files.GetTemporaryLinkResult;
import com.dropbox.core.v2.files.ListFolderContinueErrorException;
import com.dropbox.core.v2.files.ListFolderErrorException;
import com.dropbox.core.v2.files.ListFolderGetLatestCursorResult;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.LookupError;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.files.RelocationError;
import com.dropbox.core.v2.files.RelocationErrorException;
import com.dropbox.core.v2.files.RelocationResult;
import com.dropbox.core.v2.files.RestoreErrorException;
import com.dropbox.core.v2.files.ThumbnailErrorException;
import com.dropbox.core.v2.files.ThumbnailSize;
import com.dropbox.core.v2.files.UploadSessionAppendV2Uploader;
import com.dropbox.core.v2.files.UploadSessionCursor;
import com.dropbox.core.v2.files.UploadSessionFinishErrorException;
import com.dropbox.core.v2.files.UploadSessionFinishUploader;
import com.dropbox.core.v2.files.UploadSessionLookupError;
import com.dropbox.core.v2.files.UploadSessionLookupErrorException;
import com.dropbox.core.v2.files.UploadSessionStartResult;
import com.dropbox.core.v2.files.UploadSessionStartUploader;
import com.dropbox.core.v2.files.WriteConflictError;
import com.dropbox.core.v2.files.WriteError;
import com.dropbox.core.v2.files.WriteMode;
import com.dropbox.core.v2.sharing.CreateSharedLinkWithSettingsErrorException;
import com.dropbox.core.v2.sharing.ListSharedLinksErrorException;
import com.dropbox.core.v2.sharing.ListSharedLinksResult;
import com.dropbox.core.v2.sharing.SharedLinkMetadata;
import com.dropbox.core.v2.sharing.SharedLinkSettingsError;
import com.dropbox.core.v2.users.FullAccount;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.ConflictException;
import org.exoplatform.clouddrive.NotAcceptableException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.RetryLaterException;
import org.exoplatform.clouddrive.UnauthorizedException;
import org.exoplatform.clouddrive.oauth2.UserToken;
import org.exoplatform.clouddrive.utils.ChunkIterator;
import org.exoplatform.clouddrive.utils.Web;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;

/**
 * All calls to Dropbox SDK v2 here.
 * 
 */
public class DropboxAPI {

  /** The Constant VERSION. */
  public static final int    VERSION                  = 2;

  /** The Constant ROOT_URL. */
  public static final String ROOT_URL                 = "https://www.dropbox.com/home";

  /** The Constant ROOT_PATH_V2. */
  public static final String ROOT_PATH_V2             = "".intern();
  
  /** The Constant TEMP_LINK_EXPIRATION is 4hours - 5sec. */
  public static final int    TEMP_LINK_EXPIRATION     = 14395000;

  /** The Constant CHANGES_LONGPOLL_TIMEOUT. */
  public static final int    CHANGES_LONGPOLL_TIMEOUT = 60;

  /** The Constant CHANGES_LONGPOLL_URL. */
  public static final String CHANGES_LONGPOLL_URL     = "https://notify.dropboxapi.com/2/files/list_folder/longpoll";

  /** The Constant UPLOAD_LIMIT. */
  public static final int    UPLOAD_LIMIT             = 157286400;

  /** The Constant UPLOAD_BUFFER_SIZE. */
  public static final int    UPLOAD_BUFFER_SIZE       = 2048;

  /** The Constant UPLOAD_CHUNK_SIZE. */
  public static final int    UPLOAD_CHUNK_SIZE        = UPLOAD_LIMIT - UPLOAD_BUFFER_SIZE - UPLOAD_BUFFER_SIZE;

  /** Retry timeout. */
  public static final int    RETRY_TIMEOUT_MILLIS     = 1000;

  /** The Constant LOG. */
  protected static final Log LOG                      = ExoLogger.getLogger(DropboxAPI.class);

  /**
   * OAuth2 tokens storage base...
   */
  class StoredToken extends UserToken {

    /**
     * Save OAuth2 token from Dropbox API.
     *
     * @param accessToken the access token
     * @throws CloudDriveException the cloud drive exception
     */
    void store(String accessToken) throws CloudDriveException {
      this.store(accessToken, null /* refreshToken */, -1/* expirationTime */);
    }
  }

  /**
   * Dropbox folder listing with iterator over whole set of file children in Dropbox. This iterator hides
   * next-chunk logic on request to the service. <br>
   * Iterator methods can throw {@link CloudDriveException} in case of remote or communication errors.
   */
  class ListFolder extends ChunkIterator<Metadata> {

    /** The ID/path. */
    final String idPath;

    /** The cursor. */
    String       cursor;

    /** The has more. */
    boolean      hasMore;

    /**
     * Instantiates a new folder listing.
     *
     * @param idPath the folder unique identifier, an ID or a path in lower case obtained from the SDK
     * @param cursor the cursor
     * @throws NotFoundException the not found exception
     * @throws DropboxException the dropbox exception
     * @throws RefreshAccessException the refresh access exception
     * @throws RetryLaterException the retry later exception
     * @throws NotAcceptableException the not acceptable exception
     */
    ListFolder(String idPath, String cursor)
        throws NotFoundException, DropboxException, RefreshAccessException, RetryLaterException, NotAcceptableException {
      this.idPath = idPath;

      this.cursor = cursor;

      // fetch first, this also will fetch the path item metadata
      this.iter = nextChunk();
    }

    /**
     * {@inheritDoc}
     */
    protected Iterator<Metadata> nextChunk() throws NotFoundException,
                                             DropboxException,
                                             RefreshAccessException,
                                             RetryLaterException,
                                             NotAcceptableException {
      try {
        ListFolderResult result;
        if (cursor == null) {
          try {
            result = client.files().listFolder(idPath);
          } catch (ListFolderErrorException e) {
            if (e.errorValue.isPath()) {
              LookupError lookupError = e.errorValue.getPathValue();
              if (lookupError.isNotFound() || lookupError.isNotFolder()) {
                if (LOG.isDebugEnabled()) {
                  LOG.debug("Folder not found on Dropbox (to list): " + idPath, e);
                }
                result = null;
              } else if (lookupError.isMalformedPath()) {
                throw new NotAcceptableException("Malformed path: " + lookupError.getMalformedPathValue(), e);
              } else if (lookupError.isRestrictedContent()) {
                throw new NotAcceptableException("Cannot list folder due to restrictions: " + idPath, e);
              } else {
                throw new DropboxException("Failed to list folder due to path lookup error: " + idPath, e);
              }
            } else {
              throw new DropboxException("Failed to list folder: " + idPath, e);
            }
          }
        } else {
          try {
            result = client.files().listFolderContinue(cursor);
          } catch (ListFolderContinueErrorException e) {
            if (e.errorValue.isPath()) {
              LookupError lookupError = e.errorValue.getPathValue();
              if (lookupError.isNotFound() || lookupError.isNotFolder()) {
                if (LOG.isDebugEnabled()) {
                  LOG.debug("Folder not found on Dropbox (to continue list): " + idPath, e);
                }
                result = null;
              } else if (lookupError.isMalformedPath()) {
                throw new NotAcceptableException("Malformed path: " + lookupError.getMalformedPathValue(), e);
              } else if (lookupError.isRestrictedContent()) {
                throw new NotAcceptableException("Cannot continue list folder due to restrictions: " + idPath, e);
              } else {
                throw new DropboxException("Failed to continue list folder due to path lookup error: " + idPath, e);
              }
            } else if (e.errorValue.isReset()) {
              // Indicates that the cursor has been invalidated. Call list_folder to obtain a new cursor.
              throw new ResetCursorException("Reset cursor for: " + idPath, e);
            } else {
              throw new DropboxException("Failed to continue list folder: " + idPath, e);
            }
          }
        }

        if (result == null) {
          throw new NotFoundException("No file or folder found: " + idPath);
        }

        this.cursor = result.getCursor();
        this.hasMore = result.getHasMore();

        List<Metadata> children = result.getEntries();

        // Get items and let progress indicator to know the available amount
        available(children.size());

        return children.iterator();
      } catch (InvalidAccessTokenException e) {
        String msg = "Invalid access credentials";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " (access token) : " + e.getMessage(), e);
        }
        throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
      } catch (RetryException e) {
        String msg = "Dropbox overloaded or hit rate exceeded";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
      } catch (BadResponseCodeException e) {
        String msg = "Error requesting Metadata service";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      } catch (DbxException e) {
        String msg = "Error requesting Metadata service";
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

    /**
     * Gets the cursor to fetch later changes.
     *
     * @return the cursor
     */
    String getCursor() {
      return cursor;
    }
  }

  /**
   * Dropbox uses specific encoding of file paths in its URLs, it is almost the same as Java URI does, but
   * also requires encoding of such chars as ampersand etc.<br>
   * Code grabbed from here http://stackoverflow.com/questions/724043/http-url-address-encoding-in-java.
   */
  class PathEncoder {

    /**
     * Encode.
     *
     * @param input the input
     * @return the string
     */
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

    /**
     * To hex.
     *
     * @param ch the ch
     * @return the char
     */
    private char toHex(int ch) {
      return (char) (ch < 10 ? '0' + ch : 'A' + ch - 10);
    }

    /**
     * Checks if is unsafe.
     *
     * @param ch the ch
     * @return true, if is unsafe
     */
    private boolean isUnsafe(char ch) {
      if (ch > 128 || ch < 0) {
        return true;
      }
      // Do not escape slash!
      return " %$&+,:;=?@<>#%".indexOf(ch) >= 0;
    }
  }

  /** The client. */
  private DbxClientV2 client;

  /** The token. */
  private StoredToken token;

  /** The path encoder. */
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
    this.client = new DbxClientV2(config, authData.getAccessToken());

    this.token = new StoredToken();
    this.token.store(authData.getAccessToken());

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

    this.client = new DbxClientV2(config, accessToken);

    this.token = new StoredToken();
    this.token.load(accessToken, null /* refreshToken */, -1 /* expirationTime */);

    // init user (enterprise etc.)
    initUser();
  }

  /**
   * Update OAuth2 token to a new one.
   *
   * @param newToken {@link StoredToken}
   * @throws CloudDriveException the cloud drive exception
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
   * Currently connected Dropbox user.
   *
   * @return the current user
   * @throws DropboxException the dropbox exception
   * @throws RefreshAccessException the refresh access exception
   */
  FullAccount getCurrentUser() throws DropboxException, RefreshAccessException {
    try {
      return client.users().getCurrentAccount();
    } catch (DbxException e) {
      throw new DropboxException("Error requesting current account: " + e.getMessage(), e);
    }
  }

  /**
   * List folder with its children.
   *
   * @param idPath the id path
   * @return {@link ListFolder} with children items
   * @throws NotFoundException the not found exception
   * @throws DropboxException the dropbox exception
   * @throws RefreshAccessException the refresh access exception
   * @throws RetryLaterException the retry later exception
   * @throws NotAcceptableException the not acceptable exception
   */
  ListFolder listFolder(String idPath) throws NotFoundException,
                                       DropboxException,
                                       RefreshAccessException,
                                       RetryLaterException,
                                       NotAcceptableException {
    return new ListFolder(idPath, null);
  }

  /**
   * List folder continued.
   *
   * @param idPath the id path
   * @param cursor the cursor
   * @return the list folder
   * @throws NotFoundException the not found exception
   * @throws DropboxException the dropbox exception
   * @throws RefreshAccessException the refresh access exception
   * @throws RetryLaterException the retry later exception
   * @throws NotAcceptableException the not acceptable exception
   */
  ListFolder listFolderContinued(String idPath, String cursor) throws NotFoundException,
                                                               DropboxException,
                                                               RefreshAccessException,
                                                               RetryLaterException,
                                                               NotAcceptableException {
    return new ListFolder(idPath, cursor);
  }

  /**
   * Gets file or folder metadata.
   *
   * @param idPath the path or ID
   * @return the {@link Metadata}
   * @throws DropboxException the dropbox exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws RetryLaterException the retry later exception
   * @throws NotAcceptableException the not acceptable exception
   */
  Metadata get(String idPath) throws DropboxException, NotFoundException, RefreshAccessException, RetryLaterException, NotAcceptableException {
    return this.get(idPath, false);
  }

  /**
   * Gets file or folder metadata, optionally it can return deleted metadata instead of throwing
   * {@link NotFoundException} for not permanently deleted item.
   *
   * @param idPath the path or ID
   * @param includeDeleted if <code>true</code> then for deleted item it will return deleted metadata instead
   *          of throwing {@link NotFoundException} (which is for <code>false</code> value)
   * @return the {@link Metadata}
   * @throws DropboxException the dropbox exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws RetryLaterException the retry later exception
   * @throws NotAcceptableException the not acceptable exception
   */
  Metadata get(String idPath, boolean includeDeleted) throws DropboxException,
                                                      NotFoundException,
                                                      RefreshAccessException,
                                                      RetryLaterException,
                                                      NotAcceptableException {
    try {
      Metadata md;
      if (includeDeleted) {
        md = client.files().getMetadataBuilder(idPath).withIncludeDeleted(true).start();
      } else {
        md = client.files().getMetadata(idPath);
      }
      return md;
    } catch (GetMetadataErrorException e) {
      if (e.errorValue.isPath()) {
        LookupError lookupError = e.errorValue.getPathValue();
        if (lookupError.isNotFound() || lookupError.isNotFile()) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("File not found in Dropbox: " + idPath, e);
          }
          throw new NotFoundException("File not found: " + idPath, e);
        } else if (lookupError.isMalformedPath()) {
          throw new NotAcceptableException("Malformed path: " + lookupError.getMalformedPathValue(), e);
        } else if (lookupError.isRestrictedContent()) {
          throw new NotAcceptableException("Cannot read file due to restrictions: " + idPath, e);
        }
        throw new DropboxException("Failed to read file due to lookup error: " + idPath, e);
      }
      throw new DropboxException("Failed to read file: " + idPath, e);
    } catch (InvalidAccessTokenException e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
    } catch (RetryException e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
    } catch (BadResponseCodeException e) {
      String msg = "Failed to read file: " + idPath;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ". " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    } catch (DbxException e) {
      String msg = "Failed to read file: " + idPath;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ". " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    }
  }

  /**
   * Gets the content.
   *
   * @param idPath the path
   * @return the content downloader
   * @throws DropboxException the dropbox exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws NotAcceptableException the not acceptable exception
   * @throws RetryLaterException the retry later exception
   */
  DbxDownloader<FileMetadata> getContent(String idPath) throws DropboxException,
                                                        NotFoundException,
                                                        RefreshAccessException,
                                                        NotAcceptableException,
                                                        RetryLaterException {
    try {
      return client.files().download(idPath);
    } catch (DownloadErrorException e) {
      if (e.errorValue.isPath()) {
        LookupError lookupError = e.errorValue.getPathValue();
        if (lookupError.isNotFound() || lookupError.isNotFile()) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("File not found in Dropbox: " + idPath, e);
          }
          throw new NotFoundException("File not found: " + idPath, e);
        } else if (lookupError.isMalformedPath()) {
          throw new NotAcceptableException("Malformed path: " + lookupError.getMalformedPathValue(), e);
        } else if (lookupError.isRestrictedContent()) {
          throw new NotAcceptableException("Cannot download file due to restrictions: " + idPath, e);
        }
        throw new NotAcceptableException("Failed to download file due to lookup error: " + idPath, e);
      }
      throw new DropboxException("Failed to download file: " + idPath, e);
    } catch (InvalidAccessTokenException e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
    } catch (RetryException e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
    } catch (BadResponseCodeException e) {
      String msg = "Error downloading: " + idPath;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ". " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    } catch (DbxException e) {
      String msg = "Error downloading: " + idPath;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ". " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    }
  }

  /**
   * Gets the latest cursor of a folder at the path.
   *
   * @param idPath the path
   * @param recursive the recursive
   * @return the latest cursor
   * @throws DropboxException the dropbox exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws RetryLaterException the retry later exception
   * @throws NotAcceptableException the not acceptable exception
   */
  String getLatestCursor(String idPath, boolean recursive) throws DropboxException,
                                                           NotFoundException,
                                                           RefreshAccessException,
                                                           RetryLaterException,
                                                           NotAcceptableException {
    try {
      ListFolderGetLatestCursorResult res;
      if (recursive) {
        res = client.files().listFolderGetLatestCursorBuilder(idPath).withRecursive(true).start();
      } else {
        res = client.files().listFolderGetLatestCursor(idPath);
      }
      return res.getCursor();
    } catch (ListFolderErrorException e) {
      if (e.errorValue.isPath()) {
        LookupError lookupError = e.errorValue.getPathValue();
        if (lookupError.isNotFound() || lookupError.isNotFolder()) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Folder not found in Dropbox: " + idPath, e);
          }
          throw new NotFoundException("Folder not found: " + idPath, e);
        } else if (lookupError.isMalformedPath()) {
          throw new NotAcceptableException("Malformed path: " + lookupError.getMalformedPathValue(), e);
        } else if (lookupError.isRestrictedContent()) {
          throw new NotAcceptableException("Cannot read folder cursor due to restrictions: " + idPath, e);
        }
        throw new DropboxException("Failed to read folder cursor due to lookup error: " + idPath, e);
      }
      throw new DropboxException("Failed to read folder cursor: " + idPath, e);
    } catch (InvalidAccessTokenException e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
    } catch (RetryException e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
    } catch (BadResponseCodeException e) {
      String msg = "Failed to read folder cursor: " + idPath;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ". " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    } catch (DbxException e) {
      String msg = "Failed to read folder cursor: " + idPath;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ". " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    }
  }

  /**
   * Link (URL) to a file for opening by its owner on Dropbox site.
   *
   * @param parentPath the parent path
   * @param name the name
   * @return String with the file URL.
   */
  String getUserFileLink(String parentPath, String name) {
    // XXX it is undocumented URL structure observed on Dropbox
    // for files https://www.dropbox.com/home?preview=Comminications+in+Hanoi.jpg
    // file in subfolder https://www.dropbox.com/home/test/sub-folder%20N1?preview=20150713_182628.jpg
    StringBuilder link = new StringBuilder(ROOT_URL);
    if (!ROOT_PATH_V2.equals(parentPath)) {
      // FYI prior Nov 12 2015 was: link.append(Web.pathEncode(parentPath));
      link.append(pathEncoder.encode(parentPath));
    }
    link.append("?preview=").append(Web.formEncode(name));
    return link.toString();
  }

  /**
   * Link (URL) to a folder for opening by its owner on Dropbox site.
   *
   * @param path the path
   * @return String with the file URL.
   */
  String getUserFolderLink(String path) {
    // TODO can we find this kind of link via the SDK?
    // for folders https://www.dropbox.com/home/test/sub-folder%20N1
    if (ROOT_PATH_V2.equals(path)) {
      return ROOT_URL;
    } else {
      return new StringBuilder(ROOT_URL).append(pathEncoder.encode(path)).toString();
    }
  }

  /**
   * Temporary link (URL with expiration in four hours) to a file content for streaming/downloading.
   *
   * @param idPath {@link String}
   * @return DbxUrlWithExpiration with the file embed URL.
   * @throws RefreshAccessException the refresh access exception
   * @throws DropboxException the dropbox exception
   * @throws NotFoundException the not found exception
   * @throws RetryLaterException the retry later exception
   * @throws NotAcceptableException the not acceptable exception
   */
  String getDirectLink(String idPath) throws RefreshAccessException,
                                      DropboxException,
                                      NotFoundException,
                                      RetryLaterException,
                                      NotAcceptableException {
    if (ROOT_PATH_V2.equals(idPath)) {
      // no embed link for root
      return null;
    } else {
      try {
        GetTemporaryLinkResult res = client.files().getTemporaryLink(idPath);
        return res.getLink();
      } catch (GetTemporaryLinkErrorException e) {
        if (e.errorValue.isPath()) {
          LookupError lookupError = e.errorValue.getPathValue();
          if (lookupError.isNotFound() || lookupError.isNotFile() || lookupError.isNotFolder()) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("File not found in Dropbox: " + idPath, e);
            }
            throw new NotFoundException("File not found: " + idPath, e);
          } else if (lookupError.isMalformedPath()) {
            throw new NotAcceptableException("Malformed path: " + lookupError.getMalformedPathValue(), e);
          } else if (lookupError.isRestrictedContent()) {
            throw new NotAcceptableException("Cannot request file link due to restrictions: " + idPath, e);
          }
          throw new DropboxException("Failed to request file link due to lookup error: " + idPath, e);
        }
        throw new DropboxException("Failed to request file link: " + idPath, e);
      } catch (InvalidAccessTokenException e) {
        String msg = "Invalid access credentials";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " (access token) : " + e.getMessage(), e);
        }
        throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
      } catch (RetryException e) {
        String msg = "Dropbox overloaded or hit rate exceeded";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
      } catch (BadResponseCodeException e) {
        String msg = "Error requesting file link: " + idPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ". " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      } catch (DbxException e) {
        String msg = "Error requesting file link: " + idPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ". " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      }
    }
  }

  /**
   * Create shared link to a file or folder' "preview page" on Dropbox. See more on
   * <a href="https://www.dropbox.com/help/167">https://www.dropbox.com/help/167</a><br>
   *
   * @param idPath {@link String} ID or file path
   * @return {@link SharedLinkMetadata} for the shared link
   * @throws RefreshAccessException the refresh access exception
   * @throws DropboxException the dropbox exception
   * @throws RetryLaterException the retry later exception
   * @throws NotFoundException the not found exception
   * @throws NotAcceptableException the not acceptable exception
   * @throws UnauthorizedException the unauthorized exception
   */
  SharedLinkMetadata createSharedLink(String idPath) throws RefreshAccessException,
                                                  DropboxException,
                                                  RetryLaterException,
                                                  NotFoundException,
                                                  NotAcceptableException,
                                                  UnauthorizedException {
    if (ROOT_PATH_V2.equals(idPath)) {
      // no shared link for root
      return null;
    } else {
      try {
        return client.sharing().createSharedLinkWithSettings(idPath);
      } catch (CreateSharedLinkWithSettingsErrorException e) {
        if (e.errorValue.isPath()) {
          LookupError lookupError = e.errorValue.getPathValue();
          if (lookupError.isNotFound() || lookupError.isNotFile() || lookupError.isNotFolder()) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("File not found in Dropbox: " + idPath, e);
            }
            throw new NotFoundException("File not found: " + idPath, e);
          } else if (lookupError.isMalformedPath()) {
            throw new NotAcceptableException("Malformed path: " + lookupError.getMalformedPathValue(), e);
          } else if (lookupError.isRestrictedContent()) {
            throw new NotAcceptableException("Cannot request shared link due to restrictions: " + idPath, e);
          }
          throw new DropboxException("Failed to request shared link due to lookup error: " + idPath, e);
        } else if (e.errorValue.isSettingsError()) {
          SharedLinkSettingsError settingsError = e.errorValue.getSettingsErrorValue();
          if (SharedLinkSettingsError.INVALID_SETTINGS.equals(settingsError)) {
            throw new NotAcceptableException("Cannot request shared link due invalid settings: " + idPath, e);
          } else if (SharedLinkSettingsError.NOT_AUTHORIZED.equals(settingsError)) {
            throw new UnauthorizedException("User is not allowed to modify the settings of this link: " + idPath, e);
          }
          throw new NotAcceptableException("Cannot request shared link due settings error: " + idPath, e);
        } else if (e.errorValue.isAccessDenied()) {
          throw new UnauthorizedException("Access to the requested path is forbidden: " + idPath, e);
        } else if (e.errorValue.isEmailNotVerified()) {
          throw new NotAcceptableException("User email should be verified to request shared link: " + idPath, e);
        } else if (e.errorValue.isSharedLinkAlreadyExists()) {
          List<SharedLinkMetadata> links = listSharedLinks(idPath);
          if (links.size() > 0) {
            return links.get(0);
          }
          throw new NotAcceptableException("Shared link already exists but cannot be listed: " + idPath, e);
        }
        throw new DropboxException("Failed to request shared link: " + idPath, e);
      } catch (InvalidAccessTokenException e) {
        String msg = "Invalid access credentials";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " (access token) : " + e.getMessage(), e);
        }
        throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
      } catch (RetryException e) {
        String msg = "Dropbox overloaded or hit rate exceeded";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
      } catch (BadResponseCodeException e) {
        String msg = "Error requesting shared link: " + idPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ". " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      } catch (DbxException e) {
        String msg = "Error requesting shared link: " + idPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ". " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      }
    }
  }

  /**
   * List shared links.
   *
   * @param idPath the id path
   * @return the list
   * @throws RefreshAccessException the refresh access exception
   * @throws DropboxException the dropbox exception
   * @throws RetryLaterException the retry later exception
   * @throws NotFoundException the not found exception
   * @throws NotAcceptableException the not acceptable exception
   * @throws UnauthorizedException the unauthorized exception
   */
  List<SharedLinkMetadata> listSharedLinks(String idPath) throws RefreshAccessException,
                                                  DropboxException,
                                                  RetryLaterException,
                                                  NotFoundException,
                                                  NotAcceptableException,
                                                  UnauthorizedException {
    if (ROOT_PATH_V2.equals(idPath)) {
      // no shared link for root
      return null;
    } else {
      try {
        ListSharedLinksResult res = client.sharing().listSharedLinksBuilder().withPath(idPath).start();
        return res.getLinks();
      } catch (ListSharedLinksErrorException e) {
        if (e.errorValue.isPath()) {
          LookupError lookupError = e.errorValue.getPathValue();
          if (lookupError.isNotFound() || lookupError.isNotFile() || lookupError.isNotFolder()) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("File not found in Dropbox: " + idPath, e);
            }
            throw new NotFoundException("File not found: " + idPath, e);
          } else if (lookupError.isMalformedPath()) {
            throw new NotAcceptableException("Malformed path: " + lookupError.getMalformedPathValue(), e);
          } else if (lookupError.isRestrictedContent()) {
            throw new NotAcceptableException("Cannot list shared links due to restrictions: " + idPath, e);
          }
          throw new DropboxException("Failed to list shared links due to lookup error: " + idPath, e);
        }
        throw new DropboxException("Failed to list shared link: " + idPath, e);
      } catch (InvalidAccessTokenException e) {
        String msg = "Invalid access credentials";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " (access token) : " + e.getMessage(), e);
        }
        throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
      } catch (RetryException e) {
        String msg = "Dropbox overloaded or hit rate exceeded";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
      } catch (BadResponseCodeException e) {
        String msg = "Error listing shared links: " + idPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ". " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      } catch (DbxException e) {
        String msg = "Error listing shared links: " + idPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ". " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      }
    }
  }

  /**
   * Link (URL) to thumbnail of a file.
   *
   * @param idPath {@link String}
   * @param size the size
   * @return Downloader with the file thumbnail.
   * @throws DropboxException the dropbox exception
   * @throws RefreshAccessException the refresh access exception
   * @throws RetryLaterException the retry later exception
   * @throws NotAcceptableException the not acceptable exception
   * @throws NotFoundException the not found exception
   */
  DbxDownloader<FileMetadata> getThumbnail(String idPath, ThumbnailSize size) throws DropboxException,
                                                                              RefreshAccessException,
                                                                              RetryLaterException,
                                                                              NotAcceptableException,
                                                                              NotFoundException {
    if (ROOT_PATH_V2.equals(idPath)) {
      // no shared link for root
      return null;
    } else {
      try {
        return client.files().getThumbnailBuilder(idPath).withSize(size).start();
      } catch (ThumbnailErrorException e) {
        if (e.errorValue.isPath()) {
          LookupError lookupError = e.errorValue.getPathValue();
          if (lookupError.isNotFound() || lookupError.isNotFile()) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("File not found in Dropbox: " + idPath, e);
            }
            throw new NotFoundException("File not found: " + idPath, e);
          } else if (lookupError.isMalformedPath()) {
            throw new NotAcceptableException("Malformed path: " + lookupError.getMalformedPathValue(), e);
          } else if (lookupError.isRestrictedContent()) {
            throw new NotAcceptableException("Cannot get thumbnail due to restrictions: " + idPath, e);
          }
          throw new DropboxException("Failed to get thumbnail due to lookup error: " + idPath, e);
        } else if (e.errorValue.isUnsupportedImage()) {
          throw new NotAcceptableException("The image cannot be converted to a thumbnail: " + idPath, e);
        } else if (e.errorValue.isConversionError()) {
          throw new NotAcceptableException("An error occurs during thumbnail conversion: " + idPath, e);
        } else if (e.errorValue.isUnsupportedExtension()) {
          throw new NotAcceptableException("The file extension doesn't allow conversion to a thumbnail: " + idPath, e);
        }
        throw new DropboxException("Failed to get thumbnail: " + idPath, e);
      } catch (InvalidAccessTokenException e) {
        String msg = "Invalid access credentials";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + " (access token) : " + e.getMessage(), e);
        }
        throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
      } catch (RetryException e) {
        String msg = "Dropbox overloaded or hit rate exceeded";
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ": " + e.getMessage(), e);
        }
        throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
      } catch (BadResponseCodeException e) {
        String msg = "Error requesting thumbnail: " + idPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ". " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      } catch (DbxException e) {
        String msg = "Error requesting thumbnail: " + idPath;
        if (LOG.isDebugEnabled()) {
          LOG.debug(msg + ". " + e.getMessage(), e);
        }
        throw new DropboxException(msg);
      }
    }
  }

  /**
   * Handle upload lookup error (used in {@link #uploadFile(String, String, InputStream, String)}). This
   * method will extract an error and throw {@link RetryLaterException} or {@link DropboxException}.
   *
   * @param lookupError the {@link UploadSessionLookupError} instance
   * @param e the {@link DbxApiException} instance (connected to the lookup error)
   * @throws RetryLaterException the retry later exception
   * @throws DropboxException the dropbox exception
   */
  private void handleUploadLookupError(UploadSessionLookupError lookupError, DbxApiException e) throws RetryLaterException, DropboxException {
    if (lookupError.isNotFound()) {
      // Dropbox session expired (in 48hrs)
      throw new RetryLaterException("Upload session expired. Please retry", RETRY_TIMEOUT_MILLIS, e);
    } else if (lookupError.isIncorrectOffset()) {
      // The specified offset was incorrect. See the value for the correct offset. This error may occur when a
      // previous request was received and processed successfully but the client did not receive the response,
      // e.g. due to a network error.
      LOG.warn("Upload failed due to not completed request. Need retry with correct offset from Dropbox ("
          + lookupError.getIncorrectOffsetValue().getCorrectOffset() + "). " + e.getMessage());
      // FYI but we cannot continue with some offset in past as data stream already consumed (and not
      // buffered),
      // thus we need retry the whole file upload again.
      throw new RetryLaterException("Upload not complete. Please retry", RETRY_TIMEOUT_MILLIS, e);
    } else if (lookupError.isClosed()) {
      // Was attempting to append data to an upload session that has already been closed (i.e. committed).
      throw new DropboxException("Upload cannot be continued if session closed");
    } else if (lookupError.isNotClosed()) {
      // The session must be closed before calling upload_session/finish_batch.
      throw new DropboxException("Upload session must be closed before finishing");
    }
    throw new DropboxException("Upload failed", e);
  }

  /**
   * Upload file in chunks of size about 150MB as Dropbox API restricts.
   *
   * @param parentIdPath the parent ID or path
   * @param name the name
   * @param data the data
   * @param updateRev the update rev
   * @return the dbx entry. file
   * @throws DropboxException the dropbox exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   * @throws NotAcceptableException the not acceptable exception
   * @throws RetryLaterException the retry later exception
   */
  FileMetadata uploadFile(String parentIdPath, String name, InputStream data, String updateRev) throws DropboxException,
                                                                                                NotFoundException,
                                                                                                RefreshAccessException,
                                                                                                ConflictException,
                                                                                                NotAcceptableException,
                                                                                                RetryLaterException {
    String path = filePath(parentIdPath, name);
    try {
      String uploadSessionId = null;
      long uploaded = 0;
      long dataStatus = 0;
      do {
        if (uploadSessionId == null) {
          UploadSessionStartUploader uploader = client.files().uploadSessionStart();
          try {
            OutputStream out = uploader.getOutputStream();
            int[] transferRes = transferChunk(data, out);
            uploaded += transferRes[0];
            dataStatus = transferRes[1];
            UploadSessionStartResult res = uploader.finish();
            uploadSessionId = res.getSessionId();
          } finally {
            uploader.close();
          }
        } else {
          UploadSessionAppendV2Uploader uploader = client.files().uploadSessionAppendV2(new UploadSessionCursor(uploadSessionId, uploaded));
          try {
            OutputStream out = uploader.getOutputStream();
            int[] chunkRes = transferChunk(data, out);
            uploaded += chunkRes[0];
            dataStatus = chunkRes[1];
            uploader.finish();
          } catch (UploadSessionLookupErrorException e) {
            handleUploadLookupError(e.errorValue, e);
          } finally {
            uploader.close();
          }
        }

        if (dataStatus == -1) { // was end-of-stream in the data
          try {
            WriteMode writeMode = updateRev != null ? WriteMode.update(updateRev) : WriteMode.ADD;
            UploadSessionFinishUploader finish = client.files().uploadSessionFinish(new UploadSessionCursor(uploadSessionId, uploaded),
                                                                                    CommitInfo.newBuilder(path).withMode(writeMode).build());
            return finish.finish(); // return from the loop here
          } catch (UploadSessionFinishErrorException e) {
            if (e.errorValue.isLookupFailed()) {
              // session expired (48hrs)
              UploadSessionLookupError lookupError = e.errorValue.getLookupFailedValue();
              handleUploadLookupError(lookupError, e);
            } else if (e.errorValue.isPath()) {
              WriteError writeError = e.errorValue.getPathValue();
              handleWriteError(writeError, "Upload", path, e);
            } else if (e.errorValue.isTooManySharedFolderTargets()) {
              throw new DropboxException("Upload cannot be done for files into too many different shared folders", e);
            } else if (e.errorValue.isTooManyWriteOperations()) {
              throw new RetryLaterException("Upload cannot complete due too many write operations. Please retry", RETRY_TIMEOUT_MILLIS, e);
            }
            throw new DropboxException("Upload failed");
          }
        }
      } while (true);
    } catch (IllegalStateException e) {
      // Happens if the uploader has already been closed (see close) or finished (see finish)
      throw new RetryLaterException("Upload session unexpectedly closed. Please retry", RETRY_TIMEOUT_MILLIS, e);
    } catch (InvalidAccessTokenException e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
    } catch (RetryException e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
    } catch (BadResponseCodeException e) {
      String msg = "Error creating file ";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + path + ": (" + e.getStatusCode() + ")", e);
      }
      throw new DropboxException(msg + name);
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

  /**
   * Creates the folder.
   *
   * @param parentPath the parent path (not ID!)
   * @param name the name
   * @return the {@link FolderMetadata} instance
   * @throws DropboxException the dropbox exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   * @throws NotAcceptableException the rejected exception
   * @throws RetryLaterException the retry later exception
   */
  FolderMetadata createFolder(String parentPath, String name) throws DropboxException,
                                                              NotFoundException,
                                                              RefreshAccessException,
                                                              ConflictException,
                                                              NotAcceptableException,
                                                              RetryLaterException {
    String path = filePath(parentPath, name);
    try {
      CreateFolderResult res = client.files().createFolderV2(path);
      return res.getMetadata();
    } catch (CreateFolderErrorException e) {
      if (e.errorValue.isPath()) {
        WriteError writeError = e.errorValue.getPathValue();
        handleWriteError(writeError, "Folder creation", path, e);
      }
      throw new DropboxException("Folder creation failed: " + name, e);
    } catch (InvalidAccessTokenException e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
    } catch (RetryException e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
    } catch (BadResponseCodeException e) {
      String msg = "Error creating folder: " + name;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ", parentId: + " + parentPath + " (" + e.getStatusCode() + ") " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    } catch (DbxException e) {
      String msg = "Error creating folder ";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + path + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + name);
    }
  }

  /**
   * Delete a Dropbox file or folder at given path (ID or path lower-case).
   *
   * @param idPath the ID or path
   * @return the metadata of deleted item
   * @throws DropboxException the dropbox exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws NotAcceptableException the not acceptable exception
   * @throws RetryLaterException the retry later exception
   * @throws ConflictException the conflict exception
   */
  Metadata delete(String idPath) throws DropboxException,
                                 NotFoundException,
                                 RefreshAccessException,
                                 NotAcceptableException,
                                 RetryLaterException,
                                 ConflictException {
    try {
      DeleteResult res = client.files().deleteV2(idPath);
      return res.getMetadata();
    } catch (DeleteErrorException e) {
      if (e.errorValue.isPathLookup()) {
        LookupError lookupError = e.errorValue.getPathLookupValue();
        if (lookupError.isNotFound()) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("File not found on Dropbox (to delete): " + idPath, e);
          }
          throw new NotFoundException("No file found: " + idPath);
        } else if (lookupError.isNotFolder()) {
          // what a sense for this kind of error for deletion?
          throw new NotFoundException("Folder expected: " + idPath);
        } else if (lookupError.isNotFile()) {
          // what a sense for this kind of error for deletion?
          throw new NotFoundException("File expected: " + idPath);
        } else if (lookupError.isMalformedPath()) {
          throw new NotAcceptableException("Malformed path: " + lookupError.getMalformedPathValue(), e);
        } else if (lookupError.isRestrictedContent()) {
          throw new NotAcceptableException("Cannot delete due to restrictions: " + idPath, e);
        }
        throw new DropboxException("Failed to delete due to lookup error: " + idPath, e);
      } else if (e.errorValue.isPathWrite()) {
        WriteError writeError = e.errorValue.getPathWriteValue();
        handleWriteError(writeError, "Delete", idPath, e);
      } else if (e.errorValue.isTooManyFiles()) {
        throw new NotAcceptableException("Deletion of " + idPath + " involved too many files. Please retry with less of files", e);
      } else if (e.errorValue.isTooManyWriteOperations()) {
        throw new RetryLaterException("Deletion cannot complete due too many write operations. Please retry", RETRY_TIMEOUT_MILLIS, e);
      }
      throw new DropboxException("Failed to delete: " + idPath, e);
    } catch (InvalidAccessTokenException e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
    } catch (RetryException e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
    } catch (BadResponseCodeException e) {
      String msg = "Error deleting folder: " + idPath;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    } catch (DbxException e) {
      String msg = "Error deleting folder ";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + idPath + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + idPath);
    }
  }

  /**
   * Restore file.
   *
   * @param path the path of a file (not ID!)
   * @param rev the rev
   * @return the metadata
   * @throws DropboxException the dropbox exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws RetryLaterException the retry later exception
   * @throws ConflictException
   * @throws NotAcceptableException
   */
  FileMetadata restoreFile(String path, String rev) throws DropboxException,
                                                    NotFoundException,
                                                    RefreshAccessException,
                                                    RetryLaterException,
                                                    NotAcceptableException,
                                                    ConflictException {
    try {
      return client.files().restore(path, rev);
    } catch (RestoreErrorException e) {
      if (e.errorValue.isPathLookup()) {
        LookupError lookupError = e.errorValue.getPathLookupValue();
        if (lookupError.isNotFound()) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("File not found on Dropbox (to restore): " + path + " (" + rev + ")", e);
          }
          throw new NotFoundException("No file found: " + path + " (" + rev + ")");
        } else if (lookupError.isNotFolder()) {
          // what a sense for this kind of error for restoration (of deleted)?
          throw new NotFoundException("Folder expected: " + path + " (" + rev + ")");
        } else if (lookupError.isNotFile()) {
          // what a sense for this kind of error for restoration (of deleted)?
          throw new NotFoundException("File expected: " + path + " (" + rev + ")");
        } else if (lookupError.isMalformedPath()) {
          throw new NotAcceptableException("Malformed path: " + lookupError.getMalformedPathValue(), e);
        } else if (lookupError.isRestrictedContent()) {
          throw new NotAcceptableException("Cannot restore file due to restrictions: " + path + " (" + rev + ")", e);
        }
        throw new DropboxException("Failed to restore file due to lookup error: " + path + " (" + rev + ")", e);
      } else if (e.errorValue.isInvalidRevision()) {
        // The revision is invalid, but NotFoundException not a good ex to throw here
        throw new NotAcceptableException("Cannot restore file due to wrong revision: " + path + " (" + rev + ")", e);
      } else if (e.errorValue.isPathWrite()) {
        WriteError writeError = e.errorValue.getPathWriteValue();
        handleWriteError(writeError, "File restore", path, e);
      }
      throw new DropboxException("File restore failed: " + path + " (" + rev + ")", e);
    } catch (InvalidAccessTokenException e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
    } catch (RetryException e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
    } catch (BadResponseCodeException e) {
      String msg = "Error restoring file " + path;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (rev: " + rev + "): (" + e.getStatusCode() + ") " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    } catch (DbxException e) {
      String msg = "Error restoring file " + path;
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (rev: " + rev + "): " + e.getMessage(), e);
      }
      throw new DropboxException(msg);
    }
  }

  /**
   * Move.
   *
   * @param fromIdPath the from path or ID
   * @param toPath the to path
   * @return the dbx entry
   * @throws DropboxException the dropbox exception
   * @throws ConflictException the conflict exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws NotAcceptableException the not acceptable exception
   * @throws RetryLaterException the retry later exception
   */
  Metadata move(String fromIdPath, String toPath) throws DropboxException,
                                                  ConflictException,
                                                  NotFoundException,
                                                  RefreshAccessException,
                                                  NotAcceptableException,
                                                  RetryLaterException {
    try {
      RelocationResult res = client.files().moveV2(fromIdPath, toPath);
      return res.getMetadata();
    } catch (RelocationErrorException e) {
      return handleRelocationError(e.errorValue, "Move", fromIdPath, toPath, e);
    } catch (InvalidAccessTokenException e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
    } catch (RetryException e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
    } catch (BadResponseCodeException e) {
      String msg = "Error moving file";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ", " + fromIdPath + " to " + toPath + ": (" + e.getStatusCode() + ") " + e.getMessage(), e);
      }
      throw new DropboxException(msg + " " + fromIdPath);
    } catch (DbxException e) {
      String msg = "Error moving file";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ", " + fromIdPath + " to " + toPath + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + " " + fromIdPath);
    }
  }

  /**
   * Handle write error at Dropbox side. This method will extract an error and throw
   * {@link NotFoundException}, {@link ConflictException} or {@link NotAcceptableException} exception.
   *
   * @param writeError the {@link WriteError} instance
   * @param opName the operation name, will be used for new exception message
   * @param itemPath the path associated with the error
   * @param e the {@link DbxApiException} instance connected to write error
   * @throws NotFoundException the not found exception
   * @throws ConflictException the conflict exception
   * @throws NotAcceptableException the not acceptable exception
   */
  private void handleWriteError(WriteError writeError, String opName, String itemPath, DbxApiException e) throws NotFoundException,
                                                                                                          ConflictException,
                                                                                                          NotAcceptableException {
    if (writeError.isConflict()) {
      StringBuilder msg = new StringBuilder(opName + " conflict");
      if (WriteConflictError.FILE.equals(writeError.getConflictValue())) {
        msg.append(": file found at destination");
      } else if (WriteConflictError.FOLDER.equals(writeError.getConflictValue())) {
        msg.append(": folder found at destination");
      } else if (WriteConflictError.FILE_ANCESTOR.equals(writeError.getConflictValue())) {
        msg.append(": file at an ancestor path - cannot create the required parent folders");
        throw new NotFoundException(msg.toString(), e);
      } else {
        msg.append(": check if destination doesn't have such file or folder already");
      }
      throw new ConflictException(msg.toString(), e);
    } else if (writeError.isNoWritePermission()) {
      throw new NotAcceptableException(opName + " failed due to write permissions: " + itemPath, e);
    } else if (writeError.isDisallowedName()) {
      throw new NotAcceptableException(opName + " failed due to disallowed name: " + itemPath, e);
    } else if (writeError.isMalformedPath()) {
      throw new NotAcceptableException(opName + " failed due to malformed path: " + writeError.getMalformedPathValue(), e);
    } else if (writeError.isInsufficientSpace()) {
      throw new NotAcceptableException(opName + " failed due to insufficient space: " + itemPath, e);
    } else if (writeError.isTeamFolder()) {
      throw new NotAcceptableException(opName + " not possible for team folder: " + itemPath, e);
    }
    throw new NotAcceptableException(opName + " failed: " + itemPath, e);
  }

  /**
   * Handle relocation error (used in {@link #copy(String, String)} and {@link #move(String, String)}).
   *
   * @param relocationError the relocation error
   * @param opName the op name
   * @param fromPath the from path
   * @param toPath the to path
   * @param e the e
   * @return the metadata, but it never will be returned as one of exceptions will be thrown
   * @throws NotFoundException the not found exception
   * @throws ConflictException the conflict exception
   * @throws NotAcceptableException the not acceptable exception
   * @throws DropboxException the dropbox exception
   */
  Metadata handleRelocationError(RelocationError relocationError,
                                 String opName,
                                 String fromPath,
                                 String toPath,
                                 DbxApiException e) throws NotFoundException, ConflictException, NotAcceptableException, DropboxException {
    if (relocationError.isFromLookup()) {
      LookupError lookupError = relocationError.getFromLookupValue();
      if (lookupError.isNotFound() || lookupError.isNotFile() || lookupError.isNotFolder()) {
        if (LOG.isDebugEnabled()) {
          LOG.debug(opName + " source file not found in Dropbox: " + fromPath, e);
        }
        throw new NotFoundException("Source file not found: " + fromPath, e);
      } else if (lookupError.isMalformedPath()) {
        throw new NotAcceptableException("Source has malformed path: " + lookupError.getMalformedPathValue(), e);
      } else if (lookupError.isRestrictedContent()) {
        throw new NotAcceptableException(" of source file cannot be done due to restrictions: " + fromPath, e);
      }
      throw new DropboxException(opName + " failed due to lookup error: " + fromPath, e);
    } else if (relocationError.isFromWrite()) {
      // XXX weird to handle most of the following at source (from), but Dropbox API declared it, thus we do
      WriteError writeError = relocationError.getFromWriteValue();
      handleWriteError(writeError, "Source " + opName.toLowerCase(), fromPath, e);
    } else if (relocationError.isTo()) {
      WriteError writeError = relocationError.getToValue();
      handleWriteError(writeError, opName, toPath, e);
    } else if (relocationError.isCantCopySharedFolder()) {
      throw new NotAcceptableException("Cannot " + opName.toLowerCase() + " shared folder: " + fromPath, e);
    } else if (relocationError.isCantNestSharedFolder()) {
      throw new NotAcceptableException("Cannot " + opName.toLowerCase() + " shared folder " + fromPath + " into itself", e);
    } else if (relocationError.isCantMoveFolderIntoItself()) {
      throw new NotAcceptableException("Cannot " + opName.toLowerCase() + " " + fromPath + " into itself", e);
    } else if (relocationError.isCantTransferOwnership()) {
      throw new NotAcceptableException(opName + " of " + fromPath + " would result in an ownership transfer", e);
    } else if (relocationError.isTooManyFiles()) {
      throw new NotAcceptableException(opName + " of " + fromPath + " involved too many files. Please retry with less of files", e);
    } else if (relocationError.isDuplicatedOrNestedPaths()) {
      throw new NotAcceptableException("Cannot " + opName.toLowerCase() + " " + fromPath + " with duplicated/nested paths", e);
    } else if (relocationError.isInsufficientQuota()) {
      throw new NotAcceptableException(opName + " failed due to insufficient space: " + fromPath + " to " + toPath, e);
    }
    throw new DropboxException("Failed to " + opName.toLowerCase() + ": " + fromPath + " to " + toPath, e);
  }

  /**
   * Copy file to a new one. If file was successfully copied this method return new file object.
   *
   * @param fromIdPath the from path or ID
   * @param toPath the to path
   * @return {@link DbxEntry} of actually copied file.
   * @throws DropboxException the dropbox exception
   * @throws NotFoundException the not found exception
   * @throws ConflictException the conflict exception
   * @throws RefreshAccessException the refresh access exception
   * @throws RetryLaterException the retry later exception
   * @throws NotAcceptableException the not acceptable exception
   */
  Metadata copy(String fromIdPath, String toPath) throws DropboxException,
                                                  NotFoundException,
                                                  ConflictException,
                                                  RefreshAccessException,
                                                  RetryLaterException,
                                                  NotAcceptableException {

    try {
      RelocationResult res = client.files().copyV2(fromIdPath, toPath);
      return res.getMetadata();
    } catch (RelocationErrorException e) {
      return handleRelocationError(e.errorValue, "Copy", fromIdPath, toPath, e);
    } catch (InvalidAccessTokenException e) {
      String msg = "Invalid access credentials";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + " (access token) : " + e.getMessage(), e);
      }
      throw new RefreshAccessException(msg + ". Please authorize to Dropbox");
    } catch (RetryException e) {
      String msg = "Dropbox overloaded or hit rate exceeded";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ": " + e.getMessage(), e);
      }
      throw new RetryLaterException(msg + ". Please try again later (" + e.getBackoffMillis() + ")", e.getBackoffMillis());
    } catch (BadResponseCodeException e) {
      String msg = "Error copying file";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ", " + fromIdPath + " to " + toPath + ": (" + e.getStatusCode() + ") " + e.getMessage(), e);
      }
      throw new DropboxException(msg + " " + fromIdPath);
    } catch (DbxException e) {
      String msg = "Error copying file";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg + ", " + fromIdPath + " to " + toPath + ": " + e.getMessage(), e);
      }
      throw new DropboxException(msg + " " + fromIdPath);
    }
  }

  // ********* internal *********

  /**
   * Inits the user.
   *
   * @throws DropboxException the dropbox exception
   * @throws RefreshAccessException the refresh access exception
   * @throws NotFoundException the not found exception
   */
  private void initUser() throws DropboxException, RefreshAccessException, NotFoundException {
  }

  /**
   * Lower case.
   *
   * @param str the string
   * @return the string
   */
  protected String lowerCase(String str) {
    return str.toUpperCase().toLowerCase();
  }

  /**
   * Build a child path.
   *
   * @param parent the parent's ID or path (defacto both will work with Dropbox API)
   * @param name the name, it can be both all lowercase or in natural case
   * @return the string
   */
  String filePath(String parent, String name) {
    return new StringBuilder(parent).append('/').append(name).toString();
  }

  /**
   * Transfer a chunk of data from in to out stream.
   *
   * @param in the in
   * @param out the out
   * @return the array of integers, first is number of bytes transfered, second is -1 if end of stream reached
   *         or 0 otherwise
   * @throws IOException Signals that an I/O exception has occurred.
   */
  int[] transferChunk(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[UPLOAD_BUFFER_SIZE];
    int uploaded = 0;
    int len;
    while ((len = in.read(buffer)) != -1) {
      out.write(buffer, 0, len);
      uploaded += len;
      if (uploaded >= UPLOAD_CHUNK_SIZE) {
        return new int[] { uploaded, 0 };
      }
    }
    return new int[] { uploaded, -1 };
  }

}
