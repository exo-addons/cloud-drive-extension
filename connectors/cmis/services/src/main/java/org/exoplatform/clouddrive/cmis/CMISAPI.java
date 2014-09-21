/*
 * Copyright (C) 2003-2014 eXo Platform SAS.
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
package org.exoplatform.clouddrive.cmis;

import org.apache.chemistry.opencmis.client.api.ChangeEvent;
import org.apache.chemistry.opencmis.client.api.ChangeEvents;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.bindings.CmisBindingFactory;
import org.apache.chemistry.opencmis.client.bindings.spi.LinkAccess;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.ConflictException;
import org.exoplatform.clouddrive.FileTrashRemovedException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.utils.ChunkIterator;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * All calls to CMIS API here.
 * 
 */
public class CMISAPI {

  protected static final Log LOG      = ExoLogger.getLogger(CMISAPI.class);

  public static final String NO_STATE = "__no_state_set__";

  /**
   * Iterator over whole set of items from cloud service. This iterator hides next-chunk logic on
   * request to the service. <br>
   * Iterator methods can throw {@link CloudDriveException} in case of remote or communication errors.
   * 
   */
  class ItemsIterator extends ChunkIterator<CmisObject> {
    final String folderId;

    /**
     * Parent folder.
     */
    Folder       parent;

    ItemsIterator(String folderId) throws CloudDriveException {
      this.folderId = folderId;

      // fetch first
      this.iter = nextChunk();
    }

    protected Iterator<CmisObject> nextChunk() throws CloudDriveException {
      try {
        CmisObject obj = session().getObject(folderId);
        if (isFolder(obj)) {
          // it is folder
          parent = (Folder) obj;
          ItemIterable<CmisObject> children = parent.getChildren();
          // TODO reorder folders first in the iterator?
          return children.iterator();
        } else {
          // empty iterator
          return new ArrayList<CmisObject>().iterator();
        }
      } catch (CmisRuntimeException e) {
        throw new CMISException("Error getting folder items: " + e.getMessage(), e);
      }
    }

    protected boolean hasNextChunk() {
      // TODO implement pagination via cmis context
      return false;
    }
  }

  /**
   * Iterator over set of drive change events from cloud service. This iterator hides next-chunk logic on
   * request to the service. <br>
   * Iterator methods can throw {@link CMISException} in case of remote or communication errors.
   */
  class ChangesIterator extends ChunkIterator<ChangeEvent> {

    String       changeToken;

    ChangeEvents events;

    ChangesIterator(String startChangeToken) throws CMISException, RefreshAccessException {
      this.changeToken = startChangeToken;

      // fetch first
      this.iter = nextChunk();
    }

    protected Iterator<ChangeEvent> nextChunk() throws CMISException, RefreshAccessException {
      try {
        // XXX includeProperties = false, maxNumItems = max possible value to fetch all at once
        // TODO better pagination organization
        ChangeEvents events = session().getContentChanges(changeToken, false, Integer.MAX_VALUE);

        // TODO remember position for next chunk and next iterators
        changeToken = events.getLatestChangeLogToken();

        return events.getChangeEvents().iterator();
      } catch (CmisRuntimeException e) {
        throw new CMISException("Error requesting Content Changes service: " + e.getMessage(), e);
      }
    }

    /**
     * {@inheritDoc}
     */
    protected boolean hasNextChunk() {
      // TODO check if it work properly
      return events.getHasMoreItems();
    }

    String getLatestChangeLogToken() {
      return changeToken;
    }
  }

  /**
   * Sample drive state POJO.
   */
  public static class DriveState {
    final String type;

    final String url;

    final long   retryTimeout, created;

    DriveState(String type, String url, long retryTimeout) {
      this.type = type;
      this.url = url;
      this.retryTimeout = retryTimeout;
      this.created = System.currentTimeMillis();
    }

    /**
     * @return the type
     */
    public String getType() {
      return type;
    }

    /**
     * @return the url
     */
    public String getUrl() {
      return url;
    }

    /**
     * @return the retryTimeout
     */
    public long getRetryTimeout() {
      return retryTimeout;
    }

    /**
     * @return the created
     */
    public long getCreated() {
      return created;
    }

    public boolean isOutdated() {
      return (System.currentTimeMillis() - created) > retryTimeout;
    }
  }

  /**
   * Client session lock.
   */
  private final Lock             lock         = new ReentrantLock();

  /**
   * Client session parameters.
   */
  protected Map<String, String>  parameters   = new HashMap<String, String>();

  /**
   * Current CMIS repository.
   */
  protected String               repositoryId;

  protected DriveState           state;

  protected String               enterpriseId, enterpriseName, customDomain;

  protected ThreadLocal<Session> localSession = new ThreadLocal<Session>();

  /**
   * Create API from user credentials.
   * 
   * @param serviceURL {@link String}
   * @param user {@link String}
   * @param password {@link String}
   * @throws CMISException
   * @throws CloudDriveException
   */
  CMISAPI(String serviceURL, String user, String password) throws CMISException, CloudDriveException {
    // Prepare CMIS server parameters
    Map<String, String> parameters = new HashMap<String, String>();

    // User credentials.
    parameters.put(SessionParameter.USER, user);
    parameters.put(SessionParameter.PASSWORD, password);

    // Connection settings.
    parameters.put(SessionParameter.ATOMPUB_URL, serviceURL);
    // TODO
    // if (repository != null) {
    // // Only necessary if there is more than one repository.
    // parameter.put(SessionParameter.REPOSITORY_ID, repository);
    // }
    parameters.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());

    // TODO session locale
    // parameters.put(SessionParameter.LOCALE_ISO3166_COUNTRY, "");
    // parameters.put(SessionParameter.LOCALE_ISO639_LANGUAGE, "de");

    this.parameters = parameters;

    // TODO init drive state (optional)
    updateState();

    // init user (enterprise etc.)
    initUser();
  }

  /**
   * Update user credentials.
   * 
   * @param user {@link String}
   * @param password {@link String}
   * @throws CloudDriveException
   */
  void updateUser(Map<String, String> parameters) throws CloudDriveException {
    try {
      lock.lock();
      this.parameters = new HashMap<String, String>(parameters);
    } finally {
      lock.unlock();
    }
  }

  /**
   * @return CMIS session parameters.
   */
  Map<String, String> getParamaters() {
    try {
      lock.lock();
      return Collections.unmodifiableMap(parameters);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Currently connected cloud user.
   * 
   * @return String
   * @throws CMISException
   * @throws RefreshAccessException
   */
  String getUser() throws CMISException, RefreshAccessException {
    try {
      lock.lock();
      return parameters.get(SessionParameter.USER);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Password of currently connected cloud user.
   * 
   * @return String
   * @throws CMISException
   * @throws RefreshAccessException
   */
  String getPassword() throws CMISException, RefreshAccessException {
    try {
      lock.lock();
      return parameters.get(SessionParameter.PASSWORD);
    } finally {
      lock.unlock();
    }
  }

  /**
   * CMIS service's AtomPub URL.
   * 
   * @return String
   * @throws CMISException
   * @throws RefreshAccessException
   */
  String getServiceURL() throws CMISException, RefreshAccessException {
    try {
      lock.lock();
      return parameters.get(SessionParameter.ATOMPUB_URL);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Available repositories for current user.
   * 
   * @return list of {@link Repository} objects
   * @throws CMISException
   */
  List<Repository> getRepositories() throws CMISException {
    return Collections.unmodifiableList(repositories());
  }

  /**
   * Init current CMIS repository for late use.
   * 
   * @param repositoryId {@link String} repository name
   */
  void initRepository(String repositoryId) {
    this.repositoryId = repositoryId;
  }

  /**
   * Current CMIS repository.
   * 
   * @return the repository
   */
  String getRepository() {
    return repositoryId;
  }

  /**
   * The drive root folder.
   * 
   * @return {@link Folder}
   * @throws CMISException
   */
  Folder getRootFolder() throws CMISException {
    try {
      Folder root = session().getRootFolder();
      return root;
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error getting root folder: " + e.getMessage(), e);
    }
  }
  
  CmisObject getObject(String objectId) throws CMISException {
    try {
      CmisObject object = session().getObject(objectId);
      return object;
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error getting object: " + e.getMessage(), e);
    }
  }

  ItemsIterator getFolderItems(String folderId) throws CloudDriveException {
    return new ItemsIterator(folderId);
  }

  /**
   * Link (URl) to a file for opening on provider site (UI).
   * 
   * @param item {@link CmisObject}
   * @return String with the file URL.
   * @throws CMISException
   */
  String getLink(CmisObject file) throws CMISException {
    // XXX type Constants.MEDIATYPE_FEED assumed as better fit, need confirm this with the doc
    String link = ((LinkAccess) session().getBinding().getNavigationService()).loadLink(repositoryId,
                                                                                        file.getId(),
                                                                                        Constants.REL_DOWN,
                                                                                        Constants.MEDIATYPE_FEED);
    return link;
  }

  /**
   * Link (URL) to embed a file onto external app (in PLF). It is the same as file link.
   * 
   * @param item {@link CmisObject}
   * @return String with the file embed URL.
   * @throws CMISException
   * @see {@link #getLink(CmisObject)}
   */
  String getEmbedLink(CmisObject item) throws CMISException {
    return getLink(item);
  }

  DriveState getState() throws CMISException, RefreshAccessException {
    if (state == null || state.isOutdated()) {
      updateState();
    }

    return state;
  }

  /**
   * Update the drive state.
   * 
   */
  void updateState() throws CMISException, RefreshAccessException {
    try {
      // TODO get the state from cloud or any other way and construct a new state object...
      this.state = new DriveState("type...", "http://....", 10);
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error getting drive state: " + e.getMessage(), e);
    }
  }

  ChangesIterator getChanges(String changeToken) throws CMISException, RefreshAccessException {
    return new ChangesIterator(changeToken);
  }

  Document createFile(String parentId, String name, Calendar created, InputStream data) throws CMISException,
                                                                                       NotFoundException,
                                                                                       RefreshAccessException,
                                                                                       ConflictException {
    try {
      // TODO request the cloud API and create the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error creating cloud file: " + e.getMessage(), e);
    }
  }

  Folder createFolder(String parentId, String name, Calendar created) throws CMISException,
                                                                     NotFoundException,
                                                                     RefreshAccessException,
                                                                     ConflictException {
    try {
      // TODO request the cloud API and create the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error creating cloud folder: " + e.getMessage(), e);
    }
  }

  /**
   * Delete a cloud file by given fileId.
   * 
   * @param id {@link String}
   * @throws CMISException
   * @throws NotFoundException
   * @throws RefreshAccessException
   */
  void deleteFile(String id) throws CMISException, NotFoundException, RefreshAccessException {
    try {
      // TODO request the cloud API and remove the file...
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error deleteing cloud file: " + e.getMessage(), e);
    }
  }

  /**
   * Delete a cloud folder by given folderId.
   * 
   * @param id {@link String}
   * @throws CMISException
   * @throws NotFoundException
   * @throws RefreshAccessException
   */
  void deleteFolder(String id) throws CMISException, NotFoundException, RefreshAccessException {
    try {
      // TODO request the cloud API and remove the folder...
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error deleteing cloud folder: " + e.getMessage(), e);
    }
  }

  /**
   * Trash a cloud file by given fileId.
   * 
   * @param id {@link String}
   * @return {@link Document} of the file successfully moved to Trash in cloud side
   * @throws CMISException
   * @throws FileTrashRemovedException if file was permanently removed.
   * @throws NotFoundException
   * @throws RefreshAccessException
   */
  Document trashFile(String id) throws CMISException,
                               FileTrashRemovedException,
                               NotFoundException,
                               RefreshAccessException {
    try {
      // TODO request the cloud API and trash the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error trashing cloud file: " + e.getMessage(), e);
    }
  }

  /**
   * Trash a cloud folder by given folderId.
   * 
   * @param id {@link String}
   * @return {@link Folder} of the folder successfully moved to Trash in cloud side
   * @throws CMISException
   * @throws FileTrashRemovedException if folder was permanently removed.
   * @throws NotFoundException
   * @throws RefreshAccessException
   */
  Folder trashFolder(String id) throws CMISException,
                               FileTrashRemovedException,
                               NotFoundException,
                               RefreshAccessException {
    try {
      // TODO request the cloud API and untrash the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error untrashing cloud folder: " + e.getMessage(), e);
    }
  }

  Document untrashFile(String id, String name) throws CMISException,
                                              NotFoundException,
                                              RefreshAccessException,
                                              ConflictException {
    try {
      // TODO request the cloud API and untrash the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error untrashing cloud file: " + e.getMessage(), e);
    }
  }

  Folder untrashFolder(String id, String name) throws CMISException,
                                              NotFoundException,
                                              RefreshAccessException,
                                              ConflictException {
    try {
      // TODO request the cloud API and untrash the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error untrashing cloud folder: " + e.getMessage(), e);
    }
  }

  /**
   * Update file name or/and parent and set given modified date.
   * 
   * @param parentId {@link String}
   * @param id {@link String}
   * @param name {@link String}
   * @param modified {@link Calendar}
   * @return {@link Document} of actually changed file or <code>null</code> if file already exists with
   *         such name and parent.
   * @throws CMISException
   * @throws NotFoundException
   * @throws RefreshAccessException
   * @throws ConflictException
   */
  Document updateFile(String parentId, String id, String name, Calendar modified) throws CMISException,
                                                                                 NotFoundException,
                                                                                 RefreshAccessException,
                                                                                 ConflictException {

    try {
      // TODO request the cloud API and update the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error updating cloud file: " + e.getMessage(), e);
    }
  }

  Document updateFileContent(String parentId, String id, String name, Calendar modified, InputStream data) throws CMISException,
                                                                                                          NotFoundException,
                                                                                                          RefreshAccessException {
    try {
      // TODO request the cloud API and update the file content...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error updating cloud file content: " + e.getMessage(), e);
    }
  }

  /**
   * Update folder name or/and parent and set given modified date. If folder was actually updated (name or/and
   * parent changed) this method return updated folder object or <code>null</code> if folder already exists
   * with such name and parent.
   * 
   * @param parentId {@link String}
   * @param id {@link String}
   * @param name {@link String}
   * @param modified {@link Calendar}
   * @return {@link Folder} of actually changed folder or <code>null</code> if folder already exists with
   *         such name and parent.
   * @throws CMISException
   * @throws NotFoundException
   * @throws RefreshAccessException
   * @throws ConflictException
   */
  Folder updateFolder(String parentId, String id, String name, Calendar modified) throws CMISException,
                                                                                 NotFoundException,
                                                                                 RefreshAccessException,
                                                                                 ConflictException {
    try {
      // TODO request the cloud API and update the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error updating cloud folder: " + e.getMessage(), e);
    }
  }

  /**
   * Copy file to a new one. If file was successfully copied this method return new file object.
   * 
   * 
   * @param id {@link String}
   * @param parentId {@link String}
   * @param name {@link String}
   * @param modified {@link Calendar}
   * @return {@link Document} of actually copied file.
   * @throws CMISException
   * @throws NotFoundException
   * @throws RefreshAccessException
   * @throws ConflictException
   */
  Document copyFile(String id, String parentId, String name) throws CMISException,
                                                            NotFoundException,
                                                            RefreshAccessException,
                                                            ConflictException {
    try {
      // TODO request the cloud API and copy the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error copying cloud file: " + e.getMessage(), e);
    }
  }

  /**
   * Copy folder to a new one. If folder was successfully copied this method return new folder object.
   * 
   * @param id {@link String}
   * @param parentId {@link String}
   * @param name {@link String}
   * @return {@link Folder} of actually copied folder.
   * @throws CMISException
   * @throws NotFoundException
   * @throws RefreshAccessException
   * @throws ConflictException
   */
  Folder copyFolder(String id, String parentId, String name) throws CMISException,
                                                            NotFoundException,
                                                            RefreshAccessException,
                                                            ConflictException {
    try {
      // TODO request the cloud API and copy the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error copying cloud folder: " + e.getMessage(), e);
    }
  }

  Object readFile(String id) throws CMISException, NotFoundException, RefreshAccessException {
    try {
      // TODO request the cloud API and read the file...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error reading cloud file: " + e.getMessage(), e);
    }
  }

  Object readFolder(String id) throws CMISException, NotFoundException, RefreshAccessException {
    try {
      // TODO request the cloud API and read the folder...
      return new Object();
    } catch (Exception e) {
      // TODO don't catch Exception - it's bad practice, catch dedicated instead!

      // TODO catch cloud exceptions and throw CloudDriveException dedicated to the connector

      // TODO if OAuth2 related exception then check if need refresh tokens
      // usually Cloud API has a dedicated exception to catch for OAuth2
      // checkTokenState();

      throw new CMISException("Error reading cloud folder: " + e.getMessage(), e);
    }
  }

  RepositoryInfo getRepositoryInfo() throws CMISException {
    try {
      return session().getRepositoryInfo();
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error getting repository info: " + e.getMessage(), e);
    }
  }

  boolean isFolder(CmisObject object) {
    if (object.getBaseTypeId().equals(BaseTypeId.CMIS_FOLDER)) {
      return true;
    }
    return false;
  }

  boolean isDocument(CmisObject object) {
    if (object.getBaseTypeId().equals(BaseTypeId.CMIS_DOCUMENT)) {
      return true;
    }
    return false;
  }
  
  boolean isRelationship(CmisObject object) {
    if (object.getBaseTypeId().equals(BaseTypeId.CMIS_RELATIONSHIP)) {
      return true;
    }
    return false;
  }

  // ********* internal *********

  private void initUser() throws CMISException, RefreshAccessException, NotFoundException {
    // TODO additional and optional ops to init current user and its enterprise or group from cloud services
  }

  /**
   * List of repositories available on CMIS service.
   * 
   * @return list of {@link Repository} objects
   * @throws CMISException
   */
  private List<Repository> repositories() throws CMISException {
    try {
      lock.lock();
      SessionFactory sessionFactory = SessionFactoryImpl.newInstance();
      return sessionFactory.getRepositories(parameters);
    } catch (CmisConnectionException e) {
      // The server is unreachable
      throw new CMISException("CMIS server is unreachable", e);
    } catch (CmisRuntimeException e) {
      // The user/password have probably been rejected by the server.
      throw new CMISException("CMIS user rejected", e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Create CMIS session.
   * 
   * @return {@link Session}
   * @throws CMISException
   */
  private Session session() throws CMISException {
    Session session = localSession.get();
    if (session != null) {
      // TODO should we check if session still live (not closed)?
      return session;
    } else {
      for (Repository r : repositories()) {
        if (r.getName().equals(repositoryId)) {
          session = r.createSession();
          localSession.set(session);
          return session;
        }
      }
    }
    throw new CMISException("CMIS repository not found: " + repositoryId);
  }

  /**
   * Create CMIS binding instance (low-level API but with fine grained control).<br>
   * TODO not used!
   * 
   * @return {@link CmisBinding}
   * @throws CMISException
   */
  private CmisBinding binding() throws CMISException {
    CmisBindingFactory factory = CmisBindingFactory.newInstance();

    Map<String, String> sessionParameters = new HashMap<String, String>(parameters);
    if (repositoryId != null) {
      sessionParameters.put(SessionParameter.REPOSITORY_ID, repositoryId);
    }

    return factory.createCmisAtomPubBinding(parameters);
  }
}
