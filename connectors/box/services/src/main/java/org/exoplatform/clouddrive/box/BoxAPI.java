/*
 * Copyright (C) 2003-2016 eXo Platform SAS.
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
package org.exoplatform.clouddrive.box;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxAPIConnectionListener;
import com.box.sdk.BoxAPIException;
import com.box.sdk.BoxAPIRequest;
import com.box.sdk.BoxDateFormat;
import com.box.sdk.BoxEnterprise;
import com.box.sdk.BoxEvent;
import com.box.sdk.BoxFile;
import com.box.sdk.BoxFolder;
import com.box.sdk.BoxItem;
import com.box.sdk.BoxJSONResponse;
import com.box.sdk.BoxSharedLink;
import com.box.sdk.BoxTrash;
import com.box.sdk.BoxUser;
import com.box.sdk.ExoBoxEvent;
import com.box.sdk.PartialCollection;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.ConflictException;
import org.exoplatform.clouddrive.FileTrashRemovedException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.oauth2.UserToken;
import org.exoplatform.clouddrive.utils.ChunkIterator;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * New Box Content API that replaces the Box SDK v2. Code adopted from the BoxAPI worked with SDK v2.<br>
 * 
 * Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: BoxContentAPI.java 00000 Aug 19, 2015 pnedonosko $
 * 
 */
public class BoxAPI {

  /** The Constant LOG. */
  protected static final Log             LOG                         = ExoLogger.getLogger(BoxAPI.class);

  /**
   * Pagination size used within Box API.
   */
  public static final int                BOX_PAGE_SIZE               = 100;

  /**
   * Id of root folder on Box.
   */
  public static final String             BOX_ROOT_ID                 = "0";

  /**
   * Id of Trash folder on Box.
   */
  public static final String             BOX_TRASH_ID                = "1";

  /**
   * Box item_status for active items.
   */
  public static final String             BOX_ITEM_STATE_ACTIVE       = "active";

  /**
   * Box item_status for trashed items.
   */
  public static final String             BOX_ITEM_STATE_TRASHED      = "trashed";
  
  /**
   * Box item_status for deleted items.
   */
  public static final String             BOX_ITEM_STATE_DELETED      = "deleted";

  /**
   * URL of Box app.
   */
  public static final String             BOX_APP_URL                 = "https://app.box.com/";

  /**
   * Not official part of the path used in file services with Box API.
   */
  protected static final String          BOX_FILES_PATH              = "files/0/f/";

  /**
   * URL prefix for Box files' UI.
   */
  public static final String             BOX_FILE_URL                = BOX_APP_URL + BOX_FILES_PATH;

  /**
   * Extension for Box's webdoc files.
   */
  public static final String             BOX_WEBDOCUMENT_EXT         = "webdoc";

  /**
   * Custom mimetype for Box's webdoc files.
   */
  public static final String             BOX_WEBDOCUMENT_MIMETYPE    = "application/x-exo.box.webdoc";

  /**
   * Extension for Box's webdoc files.
   */
  public static final String             BOX_NOTE_EXT                = "boxnote";

  /**
   * Custom mimetype for Box's note files.
   */
  public static final String             BOX_NOTE_MIMETYPE           = "application/x-exo.box.note";

  /**
   * URL patter for Embedded UI of Box file. Based on:<br>
   * http://stackoverflow.com/questions/12816239/box-com-embedded-file-folder-viewer-code-via-api
   * http://developers.box.com/box-embed/<br>
   */
  public static final String             BOX_EMBED_URL               = "https://%sapp.box.com/embed_widget/000000000000/%s?"
      + "view=list&sort=date&theme=gray&show_parent_path=no&show_item_feed_actions=no&session_expired=true";

  /** The Constant BOX_EMBED_URL_SSO. */
  public static final String             BOX_EMBED_URL_SSO           = "https://app.box.com/login/auto_initiate_sso?enterprise_id=%s&redirect_url=%s";

  /** The Constant BOX_URL_CUSTOM_PATTERN. */
  public static final Pattern            BOX_URL_CUSTOM_PATTERN      = Pattern.compile("^https://([\\p{ASCII}]*){1}?\\.app\\.box\\.com/.*\\z");

  /** The Constant BOX_URL_MAKE_CUSTOM_PATTERN. */
  public static final Pattern            BOX_URL_MAKE_CUSTOM_PATTERN = Pattern.compile("^(https)://(app\\.box\\.com/.*)\\z");

  /** The Constant STREAM_POSITION_NOW. */
  public static final long               STREAM_POSITION_NOW         = -1;

  /**
   * Box folder type.
   */
  public static final String             FOLDER_TYPE                 = "folder";

  /** The Constant BOX_EVENTS. */
  public static final Set<BoxEvent.Type> BOX_EVENTS                  = new HashSet<BoxEvent.Type>();

  static {
    BOX_EVENTS.add(BoxEvent.Type.ITEM_CREATE);
    BOX_EVENTS.add(BoxEvent.Type.ITEM_UPLOAD);
    BOX_EVENTS.add(BoxEvent.Type.ITEM_MOVE);
    BOX_EVENTS.add(BoxEvent.Type.ITEM_COPY);
    BOX_EVENTS.add(BoxEvent.Type.ITEM_TRASH);
    BOX_EVENTS.add(BoxEvent.Type.ITEM_UNDELETE_VIA_TRASH);
    BOX_EVENTS.add(BoxEvent.Type.ITEM_RENAME);
  }

  /**
   * An array of all file/folder fields that will be requested when calling {@link ItemsIterator}.
   */
  public static final String[] ITEM_FIELDS = { "type", "id", "sequence_id", "etag", "name", "description", "size",
      "path_collection", "created_at", "modified_at", "created_by", "modified_by", "owned_by", "shared_link", "parent",
      "item_status", "item_collection" };

  /** The Constant USER_FIELDS. */
  public static final String[] USER_FIELDS = { "type", "id", "name", "login", "created_at", "modified_at", "role",
      "language", "timezone", "status", "avatar_url", "enterprise" };

  /**
   * The Class StoredToken.
   */
  class StoredToken extends UserToken implements BoxAPIConnectionListener {

    /**
     * Store.
     *
     * @throws CloudDriveException the cloud drive exception
     */
    void store() throws CloudDriveException {
      this.store(api.getAccessToken(), api.getRefreshToken(), api.getExpires());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRefresh(BoxAPIConnection api) {
      try {
        store();
      } catch (CloudDriveException e) {
        LOG.error("Error saving access token", e);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onError(BoxAPIConnection api, BoxAPIException error) {
      LOG.error("Error refreshing access token", error);
    }
  }

  /**
   * Iterator over whole set of items from Box service. This iterator hides next-chunk logic on
   * request to the service. <br>
   * Iterator methods can throw {@link BoxException} in case of remote or communication errors.
   */
  class ItemsIterator extends ChunkIterator<BoxItem.Info> {
    
    /** The parent. */
    final BoxFolder parent;

    /** The total. */
    long            offset = 0, total = 0;

    /**
     * Instantiates a new items iterator.
     *
     * @param folderId the folder id
     * @throws CloudDriveException the cloud drive exception
     */
    ItemsIterator(String folderId) throws CloudDriveException {
      this.parent = new BoxFolder(api, folderId);

      // fetch first
      this.iter = nextChunk();
    }

    /**
     * {@inheritDoc}
     */
    protected Iterator<BoxItem.Info> nextChunk() throws CloudDriveException {
      try {
        // TODO cleanup
        // for (BoxItem.Info itemInfo : parent) {
        // if (itemInfo instanceof BoxFile.Info) {
        // BoxFile.Info fileInfo = (BoxFile.Info) itemInfo;
        // // Do something with the file.
        // } else if (itemInfo instanceof BoxFolder.Info) {
        // BoxFolder.Info folderInfo = (BoxFolder.Info) itemInfo;
        // // Do something with the folder.
        // }
        // }

        PartialCollection<BoxItem.Info> items = parent.getChildrenRange(offset, BOX_PAGE_SIZE, ITEM_FIELDS);

        // total number of files in the folder
        total = items.fullSize();
        if (offset == 0) {
          available(total);
        }

        offset += items.size();

        // TODO do we really need folders first then files?
        // ArrayList<BoxItem> oitems = new ArrayList<BoxItem>();
        // // put folders first, then files
        // oitems.addAll(Utils.getTypedObjects(items, BoxFolder.class));
        // oitems.addAll(Utils.getTypedObjects(items, BoxFile.class));
        // return oitems.iterator();
        return items.iterator();
      } catch (BoxAPIException e) {
        checkTokenState(e);
        int status = e.getResponseCode();
        if (status == 404 || status == 412) {
          // not_found or precondition_failed - then folder not found
          throw new NotFoundException("Folder not found " + parent.getID(), e);
        }
        throw new BoxException("Error getting folder items: " + getErrorMessage(e), e);
      }
    }

    /**
     * {@inheritDoc}
     */
    protected boolean hasNextChunk() {
      return total > offset;
    }

    /**
     * Gets the parent.
     *
     * @return the parent
     */
    BoxFolder.Info getParent() {
      return parent.getInfo(ITEM_FIELDS);
    }
  }

  /**
   * Iterator over set of events from Box service. This iterator hides next-chunk logic on
   * request to the service. <br>
   * Iterator methods can throw {@link BoxException} in case of remote or communication errors.
   */
  class EventsIterator extends ChunkIterator<BoxEvent> {

    /**
     * Set of already fetched event Ids. Used to ignore duplicates from different requests.
     */
    final Set<String> eventIds = new HashSet<String>();

    /** The stream position. */
    Long              streamPosition;

    /** The chunk size. */
    Integer           offset   = 0, chunkSize = 0;

    /**
     * Instantiates a new events iterator.
     *
     * @param streamPosition the stream position
     * @throws CloudDriveException the cloud drive exception
     */
    EventsIterator(long streamPosition) throws CloudDriveException {
      this.streamPosition = streamPosition <= STREAM_POSITION_NOW ? STREAM_POSITION_NOW : streamPosition;

      // fetch first
      this.iter = nextChunk();
    }

    /**
     * {@inheritDoc}
     */
    protected Iterator<BoxEvent> nextChunk() throws CloudDriveException {
      try {
        StringBuilder url = new StringBuilder();
        url.append(api.getBaseURL());
        url.append("events?limit=");
        url.append(BOX_PAGE_SIZE);
        url.append("&stream_position=");
        url.append(streamPosition);
        url.append("&stream_type=changes");

        BoxAPIRequest request = new BoxAPIRequest(api, new URL(url.toString()), "GET");
        BoxJSONResponse response = (BoxJSONResponse) request.send();
        JsonObject jsonObject = JsonObject.readFrom(response.getJSON());
        JsonArray entries = jsonObject.get("entries").asArray();

        ArrayList<BoxEvent> events = new ArrayList<BoxEvent>();
        for (JsonValue entry : entries) {
          BoxEvent event = new ExoBoxEvent(api, entry.asObject());
          if (BOX_EVENTS.contains(event.getType())) {
            String id = event.getID();
            if (!eventIds.contains(id)) {
              eventIds.add(id);
              events.add(event);
            }
          }
        }

        // for next chunk and next iterators
        streamPosition = jsonObject.get("next_stream_position").asLong();

        this.chunkSize = events.size();
        return events.iterator();
      } catch (BoxAPIException e) {
        checkTokenState(e);
        throw new BoxException("Error requesting Events service: " + getErrorMessage(e), e);
      } catch (MalformedURLException e) {
        throw new CloudDriveException("Error constructing Events service URL: " + e.getMessage(), e);
      }
    }

    /**
     * {@inheritDoc}
     */
    protected boolean hasNextChunk() {
      // if something was read in previous chunk, then we may have a next chunk
      return chunkSize > 0;
    }

    /**
     * Gets the next stream position.
     *
     * @return the next stream position
     */
    long getNextStreamPosition() {
      return streamPosition;
    }
  }

  /**
   * The Class ChangesLink.
   */
  public static class ChangesLink {
    
    /** The type. */
    final String type;

    /** The url. */
    final String url;

    /** The created. */
    final long   maxRetries, retryTimeout, ttl, outdatedTimeout, created;

    /**
     * Instantiates a new changes link.
     *
     * @param type the type
     * @param url the url
     * @param ttl the ttl
     * @param maxRetries the max retries
     * @param retryTimeout the retry timeout
     */
    ChangesLink(String type, String url, long ttl, long maxRetries, long retryTimeout) {
      this.type = type;
      this.url = url;
      this.ttl = ttl;
      this.maxRetries = maxRetries;
      this.retryTimeout = retryTimeout;

      // link will be outdated in 95% of retry timeout
      this.outdatedTimeout = retryTimeout - Math.round(retryTimeout * 0.05f);
      this.created = System.currentTimeMillis();
    }

    /**
     * Gets the type.
     *
     * @return the type
     */
    public String getType() {
      return type;
    }

    /**
     * Gets the url.
     *
     * @return the url
     */
    public String getUrl() {
      return url;
    }

    /**
     * Gets the ttl.
     *
     * @return the ttl
     */
    public long getTtl() {
      return ttl;
    }

    /**
     * Gets the max retries.
     *
     * @return the maxRetries
     */
    public long getMaxRetries() {
      return maxRetries;
    }

    /**
     * Gets the retry timeout.
     *
     * @return the retryTimeout
     */
    public long getRetryTimeout() {
      return retryTimeout;
    }

    /**
     * Gets the outdated timeout.
     *
     * @return the outdatedTimeout
     */
    public long getOutdatedTimeout() {
      return outdatedTimeout;
    }

    /**
     * Gets the created.
     *
     * @return the created
     */
    public long getCreated() {
      return created;
    }

    /**
     * Checks if is outdated.
     *
     * @return true, if is outdated
     */
    public boolean isOutdated() {
      return (System.currentTimeMillis() - created) > outdatedTimeout;
    }
  }

  /** The api. */
  private final BoxAPIConnection api;

  /** The token. */
  private final StoredToken      token;

  /** The changes link. */
  private ChangesLink            changesLink;

  /** The custom domain. */
  private String                 enterpriseId, enterpriseName, customDomain;

  /**
   * Create Box API from OAuth2 authentication code.
   *
   * @param clientId {@link String} OAuth2 client_id for Box API
   * @param clientSecret {@link String}
   * @param authCode {@link String}
   * @param redirectUri the redirect uri
   * @throws BoxException if authentication failed for any reason.
   * @throws CloudDriveException if credentials store exception happen
   */
  BoxAPI(String clientId, String clientSecret, String authCode, String redirectUri)
      throws BoxException, CloudDriveException {
    try {
      this.api = new BoxAPIConnection(clientId, clientSecret, authCode);

      this.token = new StoredToken();
      // save just authorized access token in local store
      this.token.store(); 
      this.api.addListener(token);
    } catch (BoxAPIException e) {
      throw new BoxException("Error submiting authentication code: " + e.getMessage(), e);
    }

    // finally init changes link
    updateChangesLink();

    // init user (enterprise etc.)
    initUser();
  }

  /**
   * Create Box API from existing user credentials.
   * 
   * @param clientId {@link String} OAuth2 client_id for Box API
   * @param clientSecret {@link String}
   * @param accessToken {@link String}
   * @param refreshToken {@link String}
   * @param expirationTime long, token expiration time on milliseconds
   * @throws CloudDriveException if credentials store exception happen
   */
  BoxAPI(String clientId, String clientSecret, String accessToken, String refreshToken, long expirationTime)
      throws CloudDriveException {
    try {
      this.api = new BoxAPIConnection(clientId, clientSecret, accessToken, refreshToken);
      this.api.setExpires(expirationTime);

      this.token = new StoredToken();
      // for a case if access token was just refreshed by the Box SDK - save it in local store
      this.token.store();  
      this.api.addListener(token);
    } catch (BoxAPIException e) {
      throw new BoxException("Error creating client with authentication tokens: " + e.getMessage(), e);
    }

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
    this.token.merge(newToken); // TODO not sure this is a required step
    this.api.setAccessToken(this.token.getAccessToken());
    this.api.setRefreshToken(this.token.getRefreshToken());
    this.api.setExpires(this.token.getExpirationTime());
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
   * Currently connected Box user.
   *
   * @return {@link Info}
   * @throws BoxException the box exception
   * @throws RefreshAccessException the refresh access exception
   */
  BoxUser.Info getCurrentUser() throws BoxException, RefreshAccessException {
    try {
      com.box.sdk.BoxUser user = com.box.sdk.BoxUser.getCurrentUser(api);
      BoxUser.Info info = user.getInfo(USER_FIELDS);
      return info;
    } catch (BoxAPIException e) {
      checkTokenState(e);
      throw new BoxException("Error requesting current user: " + e.getMessage(), e);
    }
  }

  /**
   * The Box root folder.
   *
   * @return {@link BoxFolder}
   * @throws BoxException the box exception
   * @throws RefreshAccessException the refresh access exception
   */
  BoxFolder getRootFolder() throws BoxException, RefreshAccessException {
    try {
      BoxFolder root = BoxFolder.getRootFolder(api);
      return root;
    } catch (BoxAPIException e) {
      checkTokenState(e);
      throw new BoxException("Error getting root folder: " + e.getMessage(), e);
    }
  }

  /**
   * Gets the folder items.
   *
   * @param folderId the folder id
   * @return the folder items
   * @throws CloudDriveException the cloud drive exception
   */
  ItemsIterator getFolderItems(String folderId) throws CloudDriveException {
    return new ItemsIterator(folderId);
  }

  /**
   * Parses the date.
   *
   * @param dateString the date string
   * @return the calendar
   * @throws ParseException the parse exception
   */
  Calendar parseDate(String dateString) throws ParseException {
    Calendar calendar = Calendar.getInstance();
    Date d = BoxDateFormat.parse(dateString);
    calendar.setTime(d);
    return calendar;
  }

  /**
   * Format date.
   *
   * @param date the date
   * @return the string
   */
  String formatDate(Calendar date) {
    return BoxDateFormat.format(date.getTime());
  }

  /**
   * Link (URl) to the Box file for opening on Box site (UI).
   * 
   * @param item {@link BoxItem}
   * @return String with the file URL.
   */
  String getLink(BoxItem.Info item) {
    BoxSharedLink shared = item.getSharedLink();
    if (shared != null) {
      String link = shared.getURL();
      if (link != null) {
        return link(link);
      }
    }

    // XXX This link build not on official documentation, but from observed URLs from Box app site.
    StringBuilder link = new StringBuilder();
    link.append(link(BOX_FILE_URL));
    String id = item.getID();
    if (BOX_ROOT_ID.equals(id)) {
      link.append(id);
    } else if (item instanceof BoxFile.Info) {
      String parentId = item.getParent().getID();
      link.append(parentId);
      link.append("/1/f_");
      link.append(id);
    } else if (item instanceof BoxFolder.Info) {
      link.append(id);
      link.append('/');
      link.append(item.getName());
    } else {
      // for unknown open root folder
      link.append(BOX_ROOT_ID);
    }

    link.append("/");
    return link.toString();
  }

  /**
   * Link (URL) to embed a file onto external app (in PLF).
   * 
   * @param item {@link BoxItem.Item}
   * @return String with the file embed URL.
   */
  String getEmbedLink(BoxItem.Info item) {
    StringBuilder linkValue = new StringBuilder();
    BoxSharedLink shared = item.getSharedLink();
    if (shared != null) {
      String link = shared.getURL();
      String[] lparts = link.split("/");
      if (lparts.length > 3 && lparts[lparts.length - 2].equals("s")) {
        // XXX unofficial way of linkValue extracting from shared link
        linkValue.append("s/");
        linkValue.append(lparts[lparts.length - 1]);
      }
    }

    if (linkValue.length() == 0) {
      linkValue.append(BOX_FILES_PATH);
      // XXX This link build not on official documentation, but from observed URLs from Box app site.
      String id = item.getID();
      if (BOX_ROOT_ID.equals(id)) {
        linkValue.append(id);
      } else if (item instanceof BoxFile.Info) {
        String parentId = item.getParent().getID();
        linkValue.append(parentId);
        linkValue.append("/1/f_");
        linkValue.append(id);
      } else if (item instanceof BoxFolder.Info) {
        linkValue.append(id);
      } else {
        // for unknown open root folder
        linkValue.append(BOX_ROOT_ID);
      }
    }

    // Take in account custom domain for Enterprise on Box
    if (customDomain != null) {
      return String.format(BOX_EMBED_URL, customDomain + ".", linkValue.toString());
    } else {
      return String.format(BOX_EMBED_URL, "", linkValue.toString());
    }
  }

  /**
   * A link (URL) to the Box file thumbnail image.
   * 
   * @param item {@link BoxItem.Info}
   * @return String with the file URL.
   */
  String getThumbnailLink(BoxItem.Info item) {
    // TODO use real thumbnails from Box
    return getLink(item);
  }

  /**
   * Gets the changes link.
   *
   * @return the changes link
   * @throws BoxException the box exception
   * @throws RefreshAccessException the refresh access exception
   */
  ChangesLink getChangesLink() throws BoxException, RefreshAccessException {
    if (changesLink == null || changesLink.isOutdated()) {
      updateChangesLink();
    }

    return changesLink;
  }

  /**
   * Update link to the drive's long-polling changes notification service. This kind of service optional and
   * may not be supported. If long-polling changes notification not supported then this method will do
   * nothing.
   *
   * @throws BoxException the box exception
   * @throws RefreshAccessException the refresh access exception
   */
  void updateChangesLink() throws BoxException, RefreshAccessException {
    try {
      URL eventsUrl = new URL(api.getBaseURL() + "events");
      BoxAPIRequest request = new BoxAPIRequest(api, eventsUrl, "OPTIONS");
      BoxJSONResponse response = (BoxJSONResponse) request.send();
      JsonObject jsonObject = JsonObject.readFrom(response.getJSON());
      JsonArray entries = jsonObject.get("entries").asArray();
      if (entries.size() > 0) {
        JsonObject firstEntry = entries.get(0).asObject();

        JsonValue urlVal = firstEntry.get("url");
        String url = urlVal != null ? urlVal.asString() : null;

        JsonValue typeVal = firstEntry.get("type");
        String type = typeVal != null ? typeVal.asString() : null;

        JsonValue ttlVal = firstEntry.get("ttl");
        long ttl;
        try {
          ttl = ttlVal != null ? Long.parseLong(ttlVal.asString()) : 10;
        } catch (NumberFormatException e) {
          LOG.warn("Error parsing ttl value in Events response [" + ttlVal + "]: " + e);
          ttl = 10; // 10? What is it ttl? The number from Box docs.
        }

        JsonValue maxRetriesVal = firstEntry.get("max_retries");
        long maxRetries;
        try {
          maxRetries = maxRetriesVal != null ? Long.parseLong(maxRetriesVal.asString()) : 0;
        } catch (NumberFormatException e) {
          LOG.warn("Error parsing max_retries value in Events response [" + maxRetriesVal + "]: " + e);
          maxRetries = 2; // assume two possible attempts to try use the link
        }

        JsonValue retryTimeoutVal = firstEntry.get("retry_timeout");
        long retryTimeout; // it is in milliseconds
        try {
          retryTimeout = retryTimeoutVal != null ? retryTimeoutVal.asLong() * 1000 : 600000;
        } catch (NumberFormatException e) {
          LOG.warn("Error parsing retry_timeout value in Events response [" + retryTimeoutVal + "]: " + e);
          retryTimeout = 600000; // 600 sec = 10min (as mentioned in Box docs)
        }

        this.changesLink = new ChangesLink(type, url, ttl, maxRetries, retryTimeout);
      } else {
        throw new BoxException("Empty entries from Events service.");
      }
    } catch (BoxAPIException e) {
      checkTokenState(e);
      throw new BoxException("Error requesting Events service for long polling URL: " + e.getMessage(), e);
    } catch (MalformedURLException e) {
      throw new BoxException("Error constructing Events service URL: " + e.getMessage(), e);
    }
  }

  /**
   * Gets the events.
   *
   * @param streamPosition the stream position
   * @return the events
   * @throws CloudDriveException the cloud drive exception
   */
  EventsIterator getEvents(long streamPosition) throws CloudDriveException {
    return new EventsIterator(streamPosition);
  }

  /**
   * Creates the file.
   *
   * @param parentId the parent id
   * @param name the name
   * @param created the created
   * @param data the data
   * @return the box file. info
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  BoxFile.Info createFile(String parentId, String name, Calendar created, InputStream data) throws BoxException,
                                                                                            NotFoundException,
                                                                                            RefreshAccessException,
                                                                                            ConflictException {
    try {
      // To speedup the process we check if parent exists first.
      // How this speedups: if parent not found we will not wait for the content upload to the Box side.
      try {
        readFolder(parentId);
      } catch (NotFoundException e) {
        // parent not found
        throw new NotFoundException("Parent not found " + parentId + ". Cannot start file uploading " + name, e);
      }

      BoxFolder parent = new BoxFolder(api, parentId);
      // TODO You can optionally specify a Content-MD5 header with the SHA1 hash of the file to ensure that
      // the file is not corrupted in transit.
      return parent.uploadFile(data, name);
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then parent not found (can happen in race condition)
        throw new NotFoundException("Parent not found " + parentId + ". File uploading canceled for " + name, e);
      } else if (status == 403) {
        throw new NotFoundException("The user doesn't have access to upload a file " + name, e);
      } else if (status == 409) {
        // conflict - the same name file exists
        throw new ConflictException("File with the same name as creating already exists " + name, e);
      }
      throw new BoxException("Error uploading file: " + getErrorMessage(e), e);
    }
  }

  /**
   * Creates the folder.
   *
   * @param parentId the parent id
   * @param name the name
   * @param created the created
   * @return the box folder. info
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  BoxFolder.Info createFolder(String parentId, String name, Calendar created) throws BoxException,
                                                                              NotFoundException,
                                                                              RefreshAccessException,
                                                                              ConflictException {
    try {
      BoxFolder parent = new BoxFolder(api, parentId);
      return parent.createFolder(name);
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then parent not found
        throw new NotFoundException("Parent not found " + parentId, e);
      } else if (status == 403) {
        throw new NotFoundException("The user doesn't have access to create a folder " + name, e);
      } else if (status == 409) {
        // conflict - the same name file exists
        throw new ConflictException("File with the same name as creating already exists " + name, e);
      }
      throw new BoxException("Error creating folder: " + getErrorMessage(e), e);
    }
  }

  /**
   * Delete a cloud file by given fileId. Depending on Box enterprise settings for this user, the file will
   * either be actually deleted from Box or moved to the Trash.
   *
   * @param id {@link String}
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  void deleteFile(String id) throws BoxException, NotFoundException, RefreshAccessException {
    try {
      BoxFile file = new BoxFile(api, id);
      file.delete(); // TODO delete using etag?

      // TODO remove permanently in Box Trash
      // BoxTrash trash = new BoxTrash(api);
      // trash.deleteFile(id);
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then item not found
        throw new NotFoundException("File not found " + id, e);
      } else if (status == 403) {
        throw new NotFoundException("The user doesn't have access to the file " + id, e);
      }
      throw new BoxException("Error deleting file: " + getErrorMessage(e), e);
    }
  }

  /**
   * Delete a cloud folder by given folderId. Depending on Box enterprise settings for this user, the folder
   * will either be actually deleted from Box or moved to the Trash.
   *
   * @param id {@link String}
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  void deleteFolder(String id) throws BoxException, NotFoundException, RefreshAccessException {
    try {
      BoxFolder folder = new BoxFolder(api, id);
      folder.delete(true); // TODO delete using etag?

      // TODO remove permanently in Box Trash
      // BoxTrash trash = new BoxTrash(api);
      // trash.deleteFolder(id);
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then item not found
        throw new NotFoundException("File not found " + id, e);
      } else if (status == 403) {
        throw new NotFoundException("The user doesn't have access to the folder " + id, e);
      }
      throw new BoxException("Error deleting folder: " + getErrorMessage(e), e);
    }
  }

  /**
   * Trash a cloud file by given fileId. Depending on Box enterprise settings for this user, the file will
   * either be actually deleted from Box or moved to the Trash. If the file was actually deleted on Box, this
   * method will throw {@link FileTrashRemovedException}, and the caller code should delete the file locally
   * also.
   *
   * @param id {@link String}
   * @return {@link BoxFile.Info} of the file successfully moved to Box Trash
   * @throws BoxException the box exception
   * @throws FileTrashRemovedException if file was permanently removed.
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  BoxFile.Info trashFile(String id) throws BoxException,
                                    FileTrashRemovedException,
                                    NotFoundException,
                                    RefreshAccessException {
    try {
      BoxFile file = new BoxFile(api, id);
      file.delete(); // TODO delete using etag?

      // check if file actually removed or in the trash
      try {
        BoxTrash trash = new BoxTrash(api);
        return trash.getFileInfo(id, ITEM_FIELDS);
      } catch (BoxAPIException e) {
        checkTokenState(e);
        int status = e.getResponseCode();
        if (status == 404 || status == 412) {
          // not_found or precondition_failed - then file not found in the Trash
          // XXX throwing an exception not a best solution, but returning a boolean also can have double
          // meaning: not trashed at all or deleted instead of trashed
          throw new FileTrashRemovedException("Trashed file deleted permanently " + id);
        }
        throw new BoxException("Error reading trashed file: " + getErrorMessage(e), e);
      }
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then item not found
        throw new NotFoundException("File not found " + id, e);
      } else if (status == 403) {
        throw new NotFoundException("The user doesn't have access to the file " + id, e);
      }
      throw new BoxException("Error trashing file: " + getErrorMessage(e), e);
    }
  }

  /**
   * Trash a cloud folder by given folder Id. Depending on Box enterprise settings for this user, the folder
   * will either be actually deleted from Box or moved to the Trash. If the folder was actually deleted in
   * Box, this method will return {@link FileTrashRemovedException}, and the caller code should delete the
   * folder locally also.
   *
   * @param id {@link String}
   * @return {@link BoxFolder.Info} of the folder successfully moved to Box Trash
   * @throws BoxException the box exception
   * @throws FileTrashRemovedException if folder was permanently removed.
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  BoxFolder.Info trashFolder(String id) throws BoxException,
                                        FileTrashRemovedException,
                                        NotFoundException,
                                        RefreshAccessException {
    try {
      BoxFolder folder = new BoxFolder(api, id);
      folder.delete(true); // TODO delete using etag?

      // check if file actually removed or in the trash
      try {
        BoxTrash trash = new BoxTrash(api);
        return trash.getFolderInfo(id, ITEM_FIELDS);
      } catch (BoxAPIException e) {
        checkTokenState(e);
        int status = e.getResponseCode();
        if (status == 404 || status == 412) {
          // not_found or precondition_failed - then foler not found in the Trash
          // XXX throwing an exception not a best solution, but returning a boolean also can have double
          // meaning: not trashed at all or deleted instead of trashed
          throw new FileTrashRemovedException("Trashed folder deleted permanently " + id);
        }
        throw new BoxException("Error reading trashed foler: " + getErrorMessage(e), e);
      }
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then item not found
        throw new NotFoundException("File not found " + id, e);
      } else if (status == 403) {
        throw new NotFoundException("The user doesn't have access to the folder " + id, e);
      }
      throw new BoxException("Error trashing foler: " + getErrorMessage(e), e);
    }
  }

  /**
   * Untrash file.
   *
   * @param id the id
   * @param name the name
   * @return the box file. info
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  BoxFile.Info untrashFile(String id, String name) throws BoxException,
                                                   NotFoundException,
                                                   RefreshAccessException,
                                                   ConflictException {
    try {
      BoxTrash trash = new BoxTrash(api);
      return trash.restoreFile(id);
      // TODO name?
      // BoxItemRestoreRequestObject obj = BoxItemRestoreRequestObject.restoreItemRequestObject();
      // if (name != null) {
      // obj.setNewName(name);
      // }
      // return client.getTrashManager().restoreTrashFile(id, obj);
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then item not found
        throw new NotFoundException("Trashed file not found " + id, e);
      } else if (status == 405) {
        // method_not_allowed
        throw new NotFoundException("File not in the trash " + id, e);
      } else if (status == 409) {
        // conflict
        throw new ConflictException("File with the same name as untrashed already exists " + id, e);
      }
      throw new BoxException("Error untrashing file: " + getErrorMessage(e), e);
    }
  }

  /**
   * Untrash folder.
   *
   * @param id the id
   * @param name the name
   * @return the box folder. info
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  BoxFolder.Info untrashFolder(String id, String name) throws BoxException,
                                                       NotFoundException,
                                                       RefreshAccessException,
                                                       ConflictException {
    try {
      BoxTrash trash = new BoxTrash(api);
      return trash.restoreFolder(id);
      // TODO name?
      // BoxItemRestoreRequestObject obj = BoxItemRestoreRequestObject.restoreItemRequestObject();
      // if (name != null) {
      // obj.setNewName(name);
      // }
      // return client.getTrashManager().restoreTrashFolder(id, obj);
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then item not found
        throw new NotFoundException("Trashed folder not found " + id, e);
      } else if (status == 405) {
        // method_not_allowed
        throw new NotFoundException("Folder not in the trash " + id, e);
      } else if (status == 409) {
        // conflict
        throw new ConflictException("Folder with the same name as untrashed already exists " + id, e);
      }
      throw new BoxException("Error untrashing folder: " + getErrorMessage(e), e);
    }
  }

  /**
   * Update file name or/and parent. If file was actually updated (name or/and
   * parent changed) this method return updated file object or <code>null</code> if file already exists
   * with such name and parent.
   *
   * @param parentId {@link String}
   * @param id {@link String}
   * @param name {@link String}
   * @return {@link BoxFile.Info} of actually changed file or <code>null</code> if file already exists with
   *         such name and parent.
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  BoxFile.Info updateFile(String parentId, String id, String name) throws BoxException,
                                                                   NotFoundException,
                                                                   RefreshAccessException,
                                                                   ConflictException {

    BoxFile.Info existing = readFile(id);
    int attemts = 0;
    boolean nameChanged = !existing.getName().equals(name);
    boolean parentChanged = !existing.getParent().getID().equals(parentId);
    while ((nameChanged || parentChanged) && attemts < 3) {
      attemts++;
      try {
        // if name or parent changed - we do actual update, we ignore modified date changes
        // otherwise, if name the same, Box service will respond with error 409 (conflict)
        BoxFile file = new BoxFile(api, id);
        if (parentChanged) {
          // it's move of a file
          BoxFolder destination = new BoxFolder(api, parentId);
          return (BoxFile.Info) file.move(destination, nameChanged ? name : null);
        } else {
          // it's rename - FYI file.rename(name) doesn't return info
          BoxFile.Info info = file.new Info();
          info.setName(name);
          file.updateInfo(info);
          return info;
        }
      } catch (BoxAPIException e) {
        checkTokenState(e);
        int status = e.getResponseCode();
        if (status == 404 || status == 412) {
          // not_found or precondition_failed - then item not found
          throw new NotFoundException("File not found " + id, e);
        } else if (status == 409) {
          // conflict, try again
          if (attemts < 3) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("File with the same name as updated already exists " + id + ". Trying again.");
            }
            existing = readFile(id);
            nameChanged = !existing.getName().equalsIgnoreCase(name);
            parentChanged = !existing.getParent().getID().equals(parentId);
          } else {
            throw new ConflictException("File with the same name as updated already exists " + id);
          }
        } else {
          throw new BoxException("Error updating file: " + getErrorMessage(e), e);
        }
      }
    }
    return existing;
  }

  /**
   * Update file content.
   *
   * @param id the id
   * @param modified the modified
   * @param data the data
   * @return the box file. info
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  BoxFile.Info updateFileContent(String id, Calendar modified, InputStream data) throws BoxException,
                                                                                 NotFoundException,
                                                                                 RefreshAccessException {
    try {
      // we are uploading a new version here
      BoxFile file = new BoxFile(api, id);
      file.uploadVersion(data, modified.getTime());
      return file.getInfo(ITEM_FIELDS);
      // TODO
      // BoxFileUploadRequestObject obj = BoxFileUploadRequestObject.uploadFileRequestObject(parentId, name,
      // data);
      // obj.setLocalFileLastModifiedAt(modified.getTime());
      // obj.put("modified_at", formatDate(modified));
      // return client.getFilesManager().uploadNewVersion(id, obj);
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then item not found
        throw new NotFoundException("File not found " + id, e);
      }
      throw new BoxException("Error uploading new version of file: " + getErrorMessage(e), e);
    }
  }

  /**
   * Update folder name or/and parent. If folder was actually updated (name or/and
   * parent changed) this method return updated folder object or <code>null</code> if folder already exists
   * with such name and parent.
   *
   * @param parentId {@link String}
   * @param id {@link String}
   * @param name {@link String}
   * @return {@link BoxFolder.Info} of actually changed folder or <code>null</code> if folder already exists
   *         with
   *         such name and parent.
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  BoxFolder.Info updateFolder(String parentId, String id, String name) throws BoxException,
                                                                       NotFoundException,
                                                                       RefreshAccessException,
                                                                       ConflictException {
    BoxFolder.Info existing = readFolder(id);
    int attemts = 0;
    boolean nameChanged = !existing.getName().equals(name);
    boolean parentChanged = !existing.getParent().getID().equals(parentId);
    while ((nameChanged || parentChanged) && attemts < 3) {
      attemts++;
      // if name or parent changed - we do actual update, we ignore modified date changes
      // otherwise, if name the same, Box service will respond with error 409 (conflict)
      try {
        BoxFolder folder = new BoxFolder(api, id);
        if (parentChanged) {
          // it's move of a folder
          BoxFolder destination = new BoxFolder(api, parentId);
          return (BoxFolder.Info) folder.move(destination);
        } else {
          // it's rename - FYI folder.rename(name) doesn't return info
          BoxFolder.Info info = folder.new Info();
          info.setName(name);
          folder.updateInfo(info);
          return info;
        }
      } catch (BoxAPIException e) {
        checkTokenState(e);
        int status = e.getResponseCode();
        if (status == 404 || status == 412) {
          // not_found or precondition_failed - then item not found
          throw new NotFoundException("Folder not found " + id, e);
        } else if (status == 409) {
          // conflict, try again
          if (attemts < 3) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Folder with the same name as updated already exists " + id + ". Trying again.");
            }
            existing = readFolder(id);
            nameChanged = !existing.getName().equalsIgnoreCase(name);
            parentChanged = !existing.getParent().getID().equals(parentId);
          } else {
            throw new ConflictException("Folder with the same name as updated already exists " + id);
          }
        } else {
          throw new BoxException("Error updating folder: " + getErrorMessage(e), e);
        }
      }
    }
    return existing;
  }

  /**
   * Copy file to a new one. If file was successfully copied this method return new file object.
   *
   * @param id {@link String}
   * @param parentId {@link String}
   * @param name {@link String}
   * @return {@link BoxFile.Info} of actually copied file.
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  BoxFile.Info copyFile(String id, String parentId, String name) throws BoxException,
                                                                 NotFoundException,
                                                                 RefreshAccessException,
                                                                 ConflictException {
    try {
      BoxFile file = new BoxFile(api, id);
      BoxFolder destination = new BoxFolder(api, parentId);
      BoxFile.Info info = file.copy(destination, name);
      return info;
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then item not found
        throw new NotFoundException("File not found " + id, e);
      } else if (status == 409) {
        // conflict, try again
        if (LOG.isDebugEnabled()) {
          LOG.debug("File with the same name as copying already exists " + id + ". Trying again.");
        }
        throw new ConflictException("File with the same name as copying already exists " + id);
      }
      throw new BoxException("Error copying file: " + getErrorMessage(e), e);
    }
  }

  /**
   * Copy folder to a new one. If folder was successfully copied this method return new folder object.
   *
   * @param id {@link String}
   * @param parentId {@link String}
   * @param name {@link String}
   * @return {@link BoxFolder.Info} of actually copied folder.
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  BoxFolder.Info copyFolder(String id, String parentId, String name) throws BoxException,
                                                                     NotFoundException,
                                                                     RefreshAccessException,
                                                                     ConflictException {
    try {
      BoxFolder folder = new BoxFolder(api, id);
      BoxFolder destination = new BoxFolder(api, parentId);
      BoxFolder.Info info = folder.copy(destination, name);
      return info;
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then item not found
        throw new NotFoundException("Folder not found " + id, e);
      } else if (status == 409) {
        // conflict, try again
        if (LOG.isDebugEnabled()) {
          LOG.debug("Folder with the same name as copying already exists " + id + ". Trying again.");
        }
        throw new ConflictException("Folder with the same name as copying already exists " + id);
      }
      throw new BoxException("Error copying folder: " + getErrorMessage(e), e);
    }
  }

  /**
   * Read file.
   *
   * @param id the id
   * @return the box file. info
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  BoxFile.Info readFile(String id) throws BoxException, NotFoundException, RefreshAccessException {
    try {
      BoxFile file = new BoxFile(api, id);
      BoxFile.Info info = file.getInfo(ITEM_FIELDS);
      return info;
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then item not found
        throw new NotFoundException("File not found " + id, e);
      }
      throw new BoxException("Error reading file: " + e.getMessage(), e);
    }
  }

  /**
   * Read folder.
   *
   * @param id the id
   * @return the box folder. info
   * @throws BoxException the box exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  BoxFolder.Info readFolder(String id) throws BoxException, NotFoundException, RefreshAccessException {
    try {
      BoxFolder folder = new BoxFolder(api, id);
      BoxFolder.Info info = folder.getInfo(ITEM_FIELDS);
      return info;
    } catch (BoxAPIException e) {
      checkTokenState(e);
      int status = e.getResponseCode();
      if (status == 404 || status == 412) {
        // not_found or precondition_failed - then item not found
        throw new NotFoundException("Folder not found " + id, e);
      }
      throw new BoxException("Error reading folder: " + e.getMessage(), e);
    }
  }

  /**
   * Current user's enterprise name. Can be <code>null</code> if user doesn't belong to any enterprise.
   * 
   * @return {@link String} user's enterprise name or <code>null</code>
   */
  String getEnterpriseName() {
    return enterpriseName;
  }

  /**
   * Current user's enterprise ID. Can be <code>null</code> if user doesn't belong to any enterprise.
   * 
   * @return {@link String} user's enterprise ID or <code>null</code>
   */
  String getEnterpriseId() {
    return enterpriseId;
  }

  /**
   * Current user's custom domain (actual for enterprise users). Can be <code>null</code> if user doesn't have
   * a custom domain.
   * 
   * @return {@link String} user's custom domain or <code>null</code>
   */
  String getCustomDomain() {
    return customDomain;
  }

  // ********* internal *********

  /**
   * Gets the error message.
   *
   * @param e the e
   * @return the error message
   */
  private String getErrorMessage(BoxAPIException e) {
    if (e.getResponseCode() >= 400) {
      StringBuilder message = new StringBuilder();
      JsonObject jsonObject = JsonObject.readFrom(e.getResponse());
      JsonValue codeVal = jsonObject.get("code");
      if (codeVal != null) {
        String code = codeVal.asString();
        if (code.length() > 0) {
          message.append('[');
          message.append(code);
          message.append(']');
        }
      }
      JsonValue messageVal = jsonObject.get("message");
      if (messageVal != null) {
        if (message.length() > 0) {
          message.append(' ');
        }
        message.append(messageVal.asString());
        message.append('.');
      }
      JsonValue contextVal = jsonObject.get("context_info");
      if (contextVal != null) {
        JsonValue errorsVal = contextVal.asObject().get("errors");
        if (errorsVal != null) {
          messageVal = errorsVal.asObject().get("message");
          if (messageVal != null) {
            if (message.length() > 0) {
              message.append(' ');
            }
            message.append(messageVal.asString());
          }
        }
      }
      if (message.length() > 0) {
        return message.toString();
      }
    }
    return e.getMessage();
  }

  /**
   * Check if need new access token from user (refresh token already expired).
   *
   * @param e the e
   * @throws RefreshAccessException if client failed to refresh the access token and need new new token
   */
  private void checkTokenState(BoxAPIException e) throws RefreshAccessException {
    // TODO invalid_grant invalid_scope - parse to JSON, then read "error" param
    String resp = e.getResponse();
    if (e.getResponseCode() == 400 && resp.indexOf("invalid_grant") > 0) {
      // we need new access token (refresh token already expired here)
      throw new RefreshAccessException("Authentication failure. Reauthenticate.");
    }
  }

  /**
   * Inits the user.
   *
   * @throws BoxException the box exception
   * @throws RefreshAccessException the refresh access exception
   * @throws NotFoundException the not found exception
   */
  private void initUser() throws BoxException, RefreshAccessException, NotFoundException {
    BoxUser.Info user = getCurrentUser();

    String avatarUrl = user.getAvatarURL();

    Matcher m = BOX_URL_CUSTOM_PATTERN.matcher(avatarUrl);
    if (m.matches()) {
      // we have custom domain (actual for Enterprise users)
      customDomain = m.group(1);
    }

    BoxEnterprise enterprise = user.getEnterprise();
    if (enterprise != null) {
      enterpriseName = enterprise.getName();
      enterpriseId = enterprise.getID();
    }
  }

  /**
   * Correct file link for enterprise users with the enterprise custom domain (if it present).
   * 
   * @param fileLink {@link String}
   * @return file link optionally with added custom domain.
   */
  private String link(String fileLink) {
    if (customDomain != null) {
      Matcher m = BOX_URL_MAKE_CUSTOM_PATTERN.matcher(fileLink);
      if (m.matches()) {
        // we need add custom domain to the link host name
        return m.replaceFirst("$1://" + customDomain + ".$2");
      } // else, link already starts with custom domain (actual for Enterprise users)
    } // else, custom domain not available

    return fileLink;
  }
}
