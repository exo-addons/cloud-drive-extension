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
package org.exoplatform.clouddrive.PROVIDER_ID;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.ConflictException;
import org.exoplatform.clouddrive.FileTrashRemovedException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.oauth2.UserToken;
import org.exoplatform.clouddrive.utils.ChunkIterator;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * All calls to PROVIDER_ID Cloud API here.
 */
public class TemplateAPI {

  /** The Constant LOG. */
  protected static final Log LOG = ExoLogger.getLogger(TemplateAPI.class);

  /**
   * OAuth2 tokens storage base...
   */
  class StoredToken extends UserToken {

    /**
     * Sample method to call when have OAuth2 token from Cloud API.
     *
     * @param apiToken the api token
     * @throws CloudDriveException the cloud drive exception
     */
    void store(Object apiToken) throws CloudDriveException {
      // this.store(apiToken.getAccessToken(), apiToken.getRefreshToken(),
      // apiToken.getExpiresIn());
    }

    /**
     * Sample method to return authentication data.
     *
     * @return the auth data
     */
    Map<String, Object> getAuthData() {
      Map<String, Object> data = new HashMap<String, Object>();
      data.put("ACCESS_TOKEN", getAccessToken());
      data.put("REFRESH_TOKEN", getRefreshToken());
      data.put("EXPIRES_IN", getExpirationTime());
      data.put("TOKEN_TYPE", "bearer");
      return data;
    }
  }

  /**
   * Iterator over whole set of items from cloud service. This iterator hides
   * next-chunk logic on request to the service. <br>
   * Iterator methods can throw {@link CloudDriveException} in case of remote or
   * communication errors. TODO replace type Object to an actual type used by
   * Cloud API for drive items.<br>
   */
  class ItemsIterator extends ChunkIterator<Object> {

    /** The folder id. */
    final String folderId;

    /**
     * Parent folder. TODO Use read parent class.
     */
    Object       parent;

    /**
     * Instantiates a new items iterator.
     *
     * @param folderId the folder id
     * @throws CloudDriveException the cloud drive exception
     */
    ItemsIterator(String folderId) throws CloudDriveException {
      this.folderId = folderId;

      // fetch first
      this.iter = nextChunk();
    }

    /**
     * {@inheritDoc}
     */
    protected Iterator<Object> nextChunk() throws CloudDriveException {
      try {
        // TODO find parent if it is required for file calls...
        // parent = client.getFoldersManager().getFolder(folderId, obj);

        // TODO Get items and let progress indicator to know the available
        // amount
        // Collection items = parent.getItemCollection();
        // available(totalSize);

        // TODO use real type of the list
        ArrayList<Object> oitems = new ArrayList<Object>();
        // TODO put folders first, then files
        // oitems.addAll(folders);
        // oitems.addAll(files);
        return oitems.iterator();
      } catch (Exception e) {
        // TODO don't catch Exception - it's bad practice, catch dedicated
        // instead!

        // TODO if OAuth2 related exception then check if need refresh tokens
        // usually Cloud API has a dedicated exception to catch for OAuth2
        // checkTokenState();

        // if it is service or connectivity exception throw it as a provider
        // specific
        throw new TemplateException("Error getting folder items: " + e.getMessage(), e);
      }
    }

    /**
     * {@inheritDoc}
     */
    protected boolean hasNextChunk() {
      // TODO implement actual logic for large folders fetching
      return false;
    }
  }

  /**
   * Iterator over set of drive change events from cloud service. This iterator
   * hides next-chunk logic on request to the service. <br>
   * Iterator methods can throw {@link TemplateException} in case of remote or
   * communication errors.
   */
  class EventsIterator extends ChunkIterator<Object> {

    /** TODO optional position to fetch events. */
    long position;

    /**
     * Instantiates a new events iterator.
     *
     * @param position the position
     * @throws TemplateException the template exception
     * @throws RefreshAccessException the refresh access exception
     */
    EventsIterator(long position) throws TemplateException, RefreshAccessException {
      this.position = position;

      // fetch first
      this.iter = nextChunk();
    }

    /**
     * {@inheritDoc}
     */
    protected Iterator<Object> nextChunk() throws TemplateException, RefreshAccessException {
      try {
        // TODO implement actual logic here

        // TODO remember position for next chunk and next iterators
        // position = ec.getNextStreamPosition();

        ArrayList<Object> events = new ArrayList<Object>();
        // fill events collection or return iterator with them
        return events.iterator();
      } catch (Exception e) {
        // TODO don't catch Exception - it's bad practice, catch dedicated
        // instead!

        // TODO if OAuth2 related exception then check if need refresh tokens
        // usually Cloud API has a dedicated exception to catch for OAuth2
        // checkTokenState();

        throw new TemplateException("Error requesting Events service: " + e.getMessage(), e);
      }
    }

    /**
     * {@inheritDoc}
     */
    protected boolean hasNextChunk() {
      // TODO implement actual logic for large folders fetching
      return false;
    }

    /**
     * Gets the change id.
     *
     * @return the change id
     */
    long getChangeId() {
      // TODO find real next event position to read from the cloud provider,
      // if not available then apply local incremental ID which will let guess
      // the position for sycn
      return position + 1;
    }
  }

  /**
   * Sample drive state POJO.
   */
  public static class DriveState {

    /** The type. */
    final String type;

    /** The url. */
    final String url;

    /** The created. */
    final long   retryTimeout, created;

    /**
     * Instantiates a new drive state.
     *
     * @param type the type
     * @param url the url
     * @param retryTimeout the retry timeout
     */
    DriveState(String type, String url, long retryTimeout) {
      this.type = type;
      this.url = url;
      this.retryTimeout = retryTimeout;
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
     * Gets the retry timeout.
     *
     * @return the retryTimeout
     */
    public long getRetryTimeout() {
      return retryTimeout;
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
      return (System.currentTimeMillis() - created) > retryTimeout;
    }
  }

  /** The token. */
  private StoredToken token;

  /** The state. */
  private DriveState  state;

  /** The custom domain. */
  private String      enterpriseId, enterpriseName, customDomain;

  /**
   * Create Template API from OAuth2 authentication code.
   *
   * @param key {@link String} API key the same also as OAuth2 client_id
   * @param clientSecret {@link String}
   * @param authCode {@link String}
   * @param redirectUri the redirect uri
   * @throws TemplateException if authentication failed for any reason.
   * @throws CloudDriveException if credentials store exception happen
   */
  TemplateAPI(String key, String clientSecret, String authCode, String redirectUri)
      throws TemplateException, CloudDriveException {

    // TODO create Cloud API client and authenticate to it using given code.
    this.token = new StoredToken();

    // TODO if client support add a listener to save OAuth2 tokens in stored
    // token object.

    // TODO init drive state (optional)
    updateState();

    // init user (enterprise etc.)
    initUser();
  }

  /**
   * Create Template API from existing user credentials.
   * 
   * @param key {@link String} API key the same also as OAuth2 client_id
   * @param clientSecret {@link String}
   * @param accessToken {@link String}
   * @param refreshToken {@link String}
   * @param expirationTime long, token expiration time on milliseconds
   * @throws CloudDriveException if credentials store exception happen
   */
  TemplateAPI(String key, String clientSecret, String accessToken, String refreshToken, long expirationTime)
      throws CloudDriveException {

    // TODO create Cloud API client and authenticate it using stored token.

    this.token = new StoredToken();
    this.token.load(accessToken, refreshToken, expirationTime);

    // TODO authenticate client using stored token.

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

  // Bellow a dummy list of possible methods the API can has. It's blank field
  // here, implement everything you
  // need for your connector following the proposed try-catch sample.

  /**
   * Currently connected cloud user.
   *
   * @return the current user
   * @throws TemplateException the template exception
   * @throws RefreshAccessException the refresh access exception
   */
  Object getCurrentUser() throws TemplateException, RefreshAccessException {
    try {
      // TODO get an user from cloud client
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error requesting current user: " + e.getMessage(), e);
    }
  }

  /**
   * The drive root folder.
   *
   * @return {@link Object}
   * @throws TemplateException the template exception
   */
  Object getRootFolder() throws TemplateException {
    try {
      // return drive root folder
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error getting root folder: " + e.getMessage(), e);
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
   * Link (URl) to a file for opening on provider site (UI).
   * 
   * @param item {@link Object}
   * @return String with the file URL.
   */
  String getLink(Object item) {
    return "http://..."; // TODO return actual link for an item
  }

  /**
   * Link (URL) to embed a file onto external app (in PLF).
   * 
   * @param item {@link Object}
   * @return String with the file embed URL.
   */
  String getEmbedLink(Object item) {
    return "http://..."; // TODO return actual link for an item
  }

  /**
   * Gets the state.
   *
   * @return the state
   * @throws TemplateException the template exception
   * @throws RefreshAccessException the refresh access exception
   */
  DriveState getState() throws TemplateException, RefreshAccessException {
    if (state == null || state.isOutdated()) {
      updateState();
    }

    return state;
  }

  /**
   * Update the drive state.
   *
   * @throws TemplateException the template exception
   * @throws RefreshAccessException the refresh access exception
   */
  void updateState() throws TemplateException, RefreshAccessException {
    try {
      // TODO get the state from cloud or any other way and construct a new
      // state object...
      this.state = new DriveState("type...", "http://....", 10);
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error getting drive state: " + e.getMessage(), e);
    }
  }

  /**
   * Gets the events.
   *
   * @param streamPosition the stream position
   * @return the events
   * @throws TemplateException the template exception
   * @throws RefreshAccessException the refresh access exception
   */
  EventsIterator getEvents(long streamPosition) throws TemplateException, RefreshAccessException {
    return new EventsIterator(streamPosition);
  }

  /**
   * Creates the file.
   *
   * @param parentId the parent id
   * @param name the name
   * @param created the created
   * @param data the data
   * @return the object
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  Object createFile(String parentId, String name, Calendar created, InputStream data) throws TemplateException,
                                                                                      NotFoundException,
                                                                                      RefreshAccessException,
                                                                                      ConflictException {
    try {
      // TODO request the cloud API and create the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error creating cloud file: " + e.getMessage(), e);
    }
  }

  /**
   * Creates the folder.
   *
   * @param parentId the parent id
   * @param name the name
   * @param created the created
   * @return the object
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  Object createFolder(String parentId, String name, Calendar created) throws TemplateException,
                                                                      NotFoundException,
                                                                      RefreshAccessException,
                                                                      ConflictException {
    try {
      // TODO request the cloud API and create the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error creating cloud folder: " + e.getMessage(), e);
    }
  }

  /**
   * Delete a cloud file by given fileId.
   *
   * @param id {@link String}
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  void deleteFile(String id) throws TemplateException, NotFoundException, RefreshAccessException {
    try {
      // TODO request the cloud API and remove the file...
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error deleteing cloud file: " + e.getMessage(), e);
    }
  }

  /**
   * Delete a cloud folder by given folderId.
   *
   * @param id {@link String}
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  void deleteFolder(String id) throws TemplateException, NotFoundException, RefreshAccessException {
    try {
      // TODO request the cloud API and remove the folder...
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error deleteing cloud folder: " + e.getMessage(), e);
    }
  }

  /**
   * Trash a cloud file by given fileId.
   *
   * @param id {@link String}
   * @return {@link Object} of the file successfully moved to Trash in cloud
   *         side
   * @throws TemplateException the template exception
   * @throws FileTrashRemovedException if file was permanently removed.
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  Object trashFile(String id) throws TemplateException, FileTrashRemovedException, NotFoundException, RefreshAccessException {
    try {
      // TODO request the cloud API and trash the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error trashing cloud file: " + e.getMessage(), e);
    }
  }

  /**
   * Trash a cloud folder by given folderId.
   *
   * @param id {@link String}
   * @return {@link Object} of the folder successfully moved to Trash in cloud
   *         side
   * @throws TemplateException the template exception
   * @throws FileTrashRemovedException if folder was permanently removed.
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  Object trashFolder(String id) throws TemplateException, FileTrashRemovedException, NotFoundException, RefreshAccessException {
    try {
      // TODO request the cloud API and untrash the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error untrashing cloud folder: " + e.getMessage(), e);
    }
  }

  /**
   * Untrash file.
   *
   * @param id the id
   * @param name the name
   * @return the object
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  Object untrashFile(String id, String name) throws TemplateException,
                                             NotFoundException,
                                             RefreshAccessException,
                                             ConflictException {
    try {
      // TODO request the cloud API and untrash the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error untrashing cloud file: " + e.getMessage(), e);
    }
  }

  /**
   * Untrash folder.
   *
   * @param id the id
   * @param name the name
   * @return the object
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  Object untrashFolder(String id, String name) throws TemplateException,
                                               NotFoundException,
                                               RefreshAccessException,
                                               ConflictException {
    try {
      // TODO request the cloud API and untrash the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error untrashing cloud folder: " + e.getMessage(), e);
    }
  }

  /**
   * Update file name or/and parent and set given modified date.
   *
   * @param parentId {@link String}
   * @param id {@link String}
   * @param name {@link String}
   * @param modified {@link Calendar}
   * @return {@link Object} of actually changed file or <code>null</code> if
   *         file already exists with such name and parent.
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  Object updateFile(String parentId, String id, String name, Calendar modified) throws TemplateException,
                                                                                NotFoundException,
                                                                                RefreshAccessException,
                                                                                ConflictException {

    try {
      // TODO request the cloud API and update the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error updating cloud file: " + e.getMessage(), e);
    }
  }

  /**
   * Update file content.
   *
   * @param parentId the parent id
   * @param id the id
   * @param name the name
   * @param modified the modified
   * @param data the data
   * @return the object
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  Object updateFileContent(String parentId, String id, String name, Calendar modified, InputStream data) throws TemplateException,
                                                                                                         NotFoundException,
                                                                                                         RefreshAccessException {
    try {
      // TODO request the cloud API and update the file content...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error updating cloud file content: " + e.getMessage(), e);
    }
  }

  /**
   * Update folder name or/and parent and set given modified date. If folder was
   * actually updated (name or/and parent changed) this method return updated
   * folder object or <code>null</code> if folder already exists with such name
   * and parent.
   *
   * @param parentId {@link String}
   * @param id {@link String}
   * @param name {@link String}
   * @param modified {@link Calendar}
   * @return {@link Object} of actually changed folder or <code>null</code> if
   *         folder already exists with such name and parent.
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  Object updateFolder(String parentId, String id, String name, Calendar modified) throws TemplateException,
                                                                                  NotFoundException,
                                                                                  RefreshAccessException,
                                                                                  ConflictException {
    try {
      // TODO request the cloud API and update the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error updating cloud folder: " + e.getMessage(), e);
    }
  }

  /**
   * Copy file to a new one. If file was successfully copied this method return
   * new file object.
   *
   * @param id {@link String}
   * @param parentId {@link String}
   * @param name {@link String}
   * @return {@link Object} of actually copied file.
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  Object copyFile(String id, String parentId, String name) throws TemplateException,
                                                           NotFoundException,
                                                           RefreshAccessException,
                                                           ConflictException {
    try {
      // TODO request the cloud API and copy the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error copying cloud file: " + e.getMessage(), e);
    }
  }

  /**
   * Copy folder to a new one. If folder was successfully copied this method
   * return new folder object.
   *
   * @param id {@link String}
   * @param parentId {@link String}
   * @param name {@link String}
   * @return {@link Object} of actually copied folder.
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   * @throws ConflictException the conflict exception
   */
  Object copyFolder(String id, String parentId, String name) throws TemplateException,
                                                             NotFoundException,
                                                             RefreshAccessException,
                                                             ConflictException {
    try {
      // TODO request the cloud API and copy the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error copying cloud folder: " + e.getMessage(), e);
    }
  }

  /**
   * Read file.
   *
   * @param id the id
   * @return the object
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  Object readFile(String id) throws TemplateException, NotFoundException, RefreshAccessException {
    try {
      // TODO request the cloud API and read the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error reading cloud file: " + e.getMessage(), e);
    }
  }

  /**
   * Read folder.
   *
   * @param id the id
   * @return the object
   * @throws TemplateException the template exception
   * @throws NotFoundException the not found exception
   * @throws RefreshAccessException the refresh access exception
   */
  Object readFolder(String id) throws TemplateException, NotFoundException, RefreshAccessException {
    try {
      // TODO request the cloud API and read the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated
      // instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to
      // the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new TemplateException("Error reading cloud folder: " + e.getMessage(), e);
    }
  }

  // ********* internal *********

  /**
   * Check if need new access token from user (refresh token already expired).
   * 
   * @throws RefreshAccessException if client failed to refresh the access token
   *           and need new new token
   */
  private void checkTokenState() throws RefreshAccessException {
    // TODO do actual check in cloud API or other way to ensure OAuth2 refresh
    // token is up to date
    if (true) {
      // we need new access token (refresh token already expired here)
      throw new RefreshAccessException("Authentication failure. Reauthenticate.");
    }
  }

  /**
   * Inits the user.
   *
   * @throws TemplateException the template exception
   * @throws RefreshAccessException the refresh access exception
   * @throws NotFoundException the not found exception
   */
  private void initUser() throws TemplateException, RefreshAccessException, NotFoundException {
    // TODO additional and optional ops to init current user and its enterprise
    // or group from cloud services
  }
}
