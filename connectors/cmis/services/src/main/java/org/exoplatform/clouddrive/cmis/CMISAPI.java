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
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.bindings.CmisBindingFactory;
import org.apache.chemistry.opencmis.client.bindings.spi.LinkAccess;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.AtomPubParser;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNameConstraintViolationException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStreamNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;
import org.exoplatform.clouddrive.CloudDriveAccessException;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.ConflictException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.cmis.JCRLocalCMISDrive.LocalFile;
import org.exoplatform.clouddrive.utils.ChunkIterator;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  class ChildrenIterator extends ChunkIterator<CmisObject> {
    final String folderId;

    /**
     * Parent folder.
     */
    Folder       parent;

    ChildrenIterator(String folderId) throws CloudDriveException {
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
          // TODO accurate available number
          // long total = children.getTotalNumItems();
          // if (total == -1) {
          // total = children.getPageNumItems();
          // }
          available(children.getPageNumItems());
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

        List<ChangeEvent> changes = events.getChangeEvents();

        // TODO accurate available number
        // long total = events.getTotalNumItems();
        // if (total == -1) {
        // total = changes.size();
        // }
        available(changes.size());

        return changes.iterator();
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
   * @throws RefreshAccessException
   */
  List<Repository> getRepositories() throws CMISException, RefreshAccessException {
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
   * @throws RefreshAccessException
   */
  Folder getRootFolder() throws CMISException, RefreshAccessException {
    try {
      Folder root = session().getRootFolder();
      return root;
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error getting root folder: " + e.getMessage(), e);
    }
  }

  CmisObject getObject(String objectId) throws CMISException, NotFoundException, CloudDriveAccessException {
    try {
      CmisObject object = session().getObject(objectId);
      return object;
    } catch (CmisObjectNotFoundException e) {
      throw new NotFoundException("Error reading object: " + e.getMessage(), e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error reading object: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISException("Error reading object: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for document content reading: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document reading: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // session credentials already checked in session() method, here is something else and we don't know
      // how to deal with it
      throw new CloudDriveAccessException("Unauthorized for reading document: " + e.getMessage(), e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error reading document: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error reading document: " + e.getMessage(), e);
    }
  }

  ChildrenIterator getFolderItems(String folderId) throws CloudDriveException {
    return new ChildrenIterator(folderId);
  }

  ChangesIterator getChanges(String changeToken) throws CMISException, RefreshAccessException {
    return new ChangesIterator(changeToken);
  }

  /**
   * Link (URl) to a file for opening on provider site (UI).
   * 
   * @param item {@link CmisObject}
   * @return String with the file URL.
   * @throws CMISException
   * @throws RefreshAccessException
   */
  String getLink(CmisObject file) throws CMISException, RefreshAccessException {
    // XXX type Constants.MEDIATYPE_FEED assumed as better fit, need confirm this with the doc
    // TODO org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.LINK_HREF,

    LinkAccess link = (LinkAccess) session().getBinding().getObjectService();

    String linkSelfEntry = link.loadLink(repositoryId,
                                         file.getId(),
                                         Constants.REL_SELF,
                                         Constants.MEDIATYPE_ENTRY);

    String linkContent = link.loadContentLink(repositoryId, file.getId());

    // String prefix = "OBJ LINK (" + file.getId() + " " + file.getName() + ") ";
    // LOG.info(prefix + " linkSelfEntry: " + linkSelfEntry);
    // LOG.info(prefix + " linkContent: " + linkContent);

    return linkContent != null ? linkContent : linkSelfEntry;
  }

  /**
   * Link (URl) to a folder for downloading from provider site.
   * 
   * @param item {@link Document}
   * @return String with the file URL.
   * @throws CMISException
   * @throws RefreshAccessException
   */
  String getLink(Folder file) throws CMISException, RefreshAccessException {
    LinkAccess link = (LinkAccess) session().getBinding().getObjectService();

    String linkSelfEntry = link.loadLink(repositoryId,
                                         file.getId(),
                                         Constants.REL_SELF,
                                         Constants.MEDIATYPE_ENTRY);

    String linkContent = link.loadContentLink(repositoryId, file.getId());
    // String prefix = "FOLDER LINK (" + file.getId() + " " + file.getName() + ") ";
    // LOG.info(prefix + " linkSelfEntry: " + linkSelfEntry);
    // LOG.info(prefix + " linkContent: " + linkContent);

    return linkSelfEntry;
  }

  /**
   * Link (URL) to embed a file onto external app (in PLF). It is the same as file link.
   * 
   * @param item {@link CmisObject}
   * @return String with the file embed URL.
   * @throws CMISException
   * @throws RefreshAccessException
   * @see {@link #getLink(CmisObject)}
   */
  String getEmbedLink(CmisObject item) throws CMISException, RefreshAccessException {
    return getLink(item);
  }

  /**
   * Link (URL) to embed a folder onto external app (in PLF). It is the same as file link.
   * 
   * @param folder {@link Folder}
   * @return String with the file embed URL.
   * @throws CMISException
   * @throws RefreshAccessException
   * @see {@link #getLink(CmisObject)}
   */
  String getEmbedLink(Folder folder) throws CMISException, RefreshAccessException {
    return getLink(folder);
  }

  /**
   * Link (URL) to embed a document onto external app (in PLF). It is the same as document link.
   * 
   * @param doc {@link Document}
   * @return String with the file embed URL.
   * @throws CMISException
   * @throws RefreshAccessException
   * @see {@link #getLink(CmisObject)}
   */
  String getEmbedLink(Document doc) throws CMISException, RefreshAccessException {
    return getLink(doc);
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

  Document createDocument(String parentId, String name, String mimeType, InputStream data) throws CMISException,
                                                                                          NotFoundException,
                                                                                          ConflictException,
                                                                                          CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = session.getObject(parentId);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Parent not found: " + parentId, e);
      }
      if (isFolder(obj)) {
        Folder parent = (Folder) obj;

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.OBJECT_TYPE_ID, BaseTypeId.CMIS_DOCUMENT.value());
        properties.put(PropertyIds.NAME, name);
        // created date not used as CMIS will set its own one
        // properties.put(PropertyIds.CREATION_DATE, created);

        // content length = -1 if it is unknown
        ContentStream contentStream = session.getObjectFactory()
                                             .createContentStream(name, -1, mimeType, data);
        return parent.createDocument(properties, contentStream, VersioningState.MAJOR);
      } else {
        throw new CMISException("Parent not a folder: " + parentId + ", " + obj.getName());
      }
    } catch (CmisUpdateConflictException e) {
      // conflict actual for update/deletion/move
      throw new ConflictException("Document update conflict for '" + name + "'", e);
    } catch (CmisObjectNotFoundException e) {
      // this can be a rice condition when parent just deleted or similar happened remotely
      throw new NotFoundException("Error creating document: " + e.getMessage(), e);
    } catch (CmisNameConstraintViolationException e) {
      // name constraint considered as conflict (requires another name)
      // TODO check cyclic loop not possible due to infinite error - change name - error - change...
      throw new ConflictException("Unable to create document with name '" + name
          + "' due to repository constraints", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new CMISException("Unable to create document '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error creating document: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISException("Error creating document: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for document content upload: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document creation: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // session credentials already checked in session() method, here is something else and we don't know
      // how to deal with it
      throw new CloudDriveAccessException("Unauthorized for create document: " + e.getMessage(), e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error creating document: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error creating document: " + e.getMessage(), e);
    }
  }

  Folder createFolder(String parentId, String name) throws CMISException,
                                                   NotFoundException,
                                                   ConflictException,
                                                   CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = session.getObject(parentId);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Parent not found: " + parentId, e);
      }
      if (isFolder(obj)) {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.OBJECT_TYPE_ID, BaseTypeId.CMIS_FOLDER.value());
        properties.put(PropertyIds.NAME, name);
        // created date not used as CMIS will set its own one
        Folder parent = (Folder) obj;
        return parent.createFolder(properties);
      } else {
        throw new CMISException("Parent not a folder: " + parentId + ", " + obj.getName());
      }
    } catch (CmisObjectNotFoundException e) {
      // this can be a rice condition when parent just deleted or similar happened remotely
      throw new NotFoundException("Error creating folder: " + e.getMessage(), e);
    } catch (CmisNameConstraintViolationException e) {
      // name constraint considered as conflict (requires another name)
      // TODO check cyclic loop not possible due to infinite error - change name - error - change...
      throw new ConflictException("Unable create folder with name '" + name
          + "' due to repository constraints", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new CMISException("Unable create folder '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error creating folder: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISException("Error creating folder: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for folder creation: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // session credentials already checked in session() method, here is something else and we don't know
      // how to deal with it
      throw new CloudDriveAccessException("Unauthorized to create folder: " + e.getMessage(), e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error creating folder: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error creating folder: " + e.getMessage(), e);
    }
  }

  /**
   * Delete a cloud file by given fileId.
   * 
   * @param id {@link String}
   * @throws CMISException
   * @throws NotFoundException
   * @throws ConflictException
   * @throws CloudDriveAccessException
   */
  void deleteDocument(String id) throws CMISException,
                                NotFoundException,
                                ConflictException,
                                CloudDriveAccessException {
    Session session = session();
    String name = "";
    try {
      CmisObject obj = session.getObject(id);
      name = obj.getName();
      obj.delete(true);
    } catch (CmisObjectNotFoundException e) {
      throw new NotFoundException("Document not found: " + id, e);
    } catch (CmisUpdateConflictException e) {
      // conflict actual for update/deletion/move
      throw new ConflictException("Document removal conflict for '" + name + "'", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new CMISException("Unable delete document '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error deleting document: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISException("Error deleting document: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document removal: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // session credentials already checked in session() method, here is something else and we don't know
      // how to deal with it
      throw new CloudDriveAccessException("Unauthorized to delete document: " + e.getMessage(), e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error deleting document: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error deleting document: " + e.getMessage(), e);
    }
  }

  /**
   * Delete a cloud folder by given folderId.
   * 
   * @param id {@link String}
   * @throws CMISException
   * @throws NotFoundException
   * @throws ConflictException
   * @throws CloudDriveAccessException
   */
  void deleteFolder(String id) throws CMISException,
                              NotFoundException,
                              ConflictException,
                              CloudDriveAccessException {
    Session session = session();
    String name = "";
    try {
      CmisObject obj = session.getObject(id);
      name = obj.getName();
      if (isFolder(obj)) {
        Folder folder = (Folder) obj;
        folder.deleteTree(true, UnfileObject.DELETE, false);
      } else {
        throw new CMISException("Not a folder: " + id + ", " + name);
      }
    } catch (CmisObjectNotFoundException e) {
      throw new NotFoundException("Error deleting folder: " + e.getMessage(), e);
    } catch (CmisUpdateConflictException e) {
      throw new ConflictException("Folder removal conflict for '" + name + "'", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new CMISException("Unable to delete folder '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error deleting folder: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISException("Error deleting folder: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for folder removal: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // session credentials already checked in session() method, here is something else and we don't know
      // how to deal with it
      throw new CloudDriveAccessException("Unauthorized for deleting folder: " + e.getMessage(), e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error deleting folder: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error deleting folder: " + e.getMessage(), e);
    }
  }

  /**
   * Update document name (if differs with remote) and its content stream.
   * 
   * @param id {@link String}
   * @param name {@link String}
   * @param data {@link InputStream} content stream
   * @param mimeType {@link String} mime-type of the content stream
   * @param local {@link LocalFile} access to local file for move operation support
   * @return {@link Document} of actually changed document
   * @throws CMISException
   * @throws NotFoundException
   * @throws ConflictException
   * @throws CloudDriveAccessException
   */
  Document updateContent(String id, String name, InputStream data, String mimeType, LocalFile local) throws CMISException,
                                                                                                    NotFoundException,
                                                                                                    ConflictException,
                                                                                                    CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = session.getObject(id);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Document not found: " + id, e);
      }
      if (isDocument(obj)) {
        Document document = (Document) obj;

        // update name to local
        if (!document.getName().equals(name)) {
          Map<String, Object> properties = new HashMap<String, Object>();
          properties.put(PropertyIds.NAME, name);

          // update document properties
          ObjectId objId = document.updateProperties(properties, true);
          if (objId != null && objId instanceof Document) {
            document = (Document) objId;
          }
        }

        // content length = -1 if it is unknown
        ContentStream contentStream = session.getObjectFactory()
                                             .createContentStream(name, -1, mimeType, data);
        Document updatedDocument = document.setContentStream(contentStream, true);
        if (updatedDocument != null) {
          document = updatedDocument;
        }

        return document; // resulting document updated to reflect remote changes
      } else {
        throw new CMISException("Object not a document: " + id + ", " + obj.getName());
      }
    } catch (CmisContentAlreadyExistsException e) {
      // conflict actual for setContentStream only
      throw new ConflictException("Document content already exists for '" + name
          + "' and overwrite not requested", e);
    } catch (CmisUpdateConflictException e) {
      // conflict actual for update/deletion/move
      throw new ConflictException("Conflict of document updating for '" + name + "'", e);
    } catch (CmisObjectNotFoundException e) {
      // this can be a rice condition when parent just deleted or similar happened remotely
      throw new NotFoundException("Error updating document: " + e.getMessage(), e);
    } catch (CmisNameConstraintViolationException e) {
      // name constraint considered as conflict (requires another name)
      // TODO check cyclic loop not possible due to infinite error - change name - error - change...
      throw new ConflictException("Unable to update document with name '" + name
          + "' due to repository constraints", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new CMISException("Unable to update document '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error updating cloud document: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISException("Error updating cloud document: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for document content update: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document updating: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // session credentials already checked in session() method, here is something else and we don't know
      // how to deal with it
      throw new CloudDriveAccessException("Unauthorized for updating document: " + e.getMessage(), e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error updating document: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error updating document: " + e.getMessage(), e);
    }
  }

  /**
   * Update CMIS object name and parent (if object was moved locally).
   * 
   * @param parentId {@link String}
   * @param id {@link String}
   * @param name {@link String}
   * @param data {@link InputStream} content stream or <code>null</code> if content should not be updated
   * @param mimeType {@link String} mime-type of the content stream or <code>null</code> if content not
   *          provided
   * @param local {@link LocalFile} access to local file for move operation support
   * @return {@link CmisObject} of actually changed object or <code>null</code> if it already exists with
   *         such name and parent.
   * @throws CMISException
   * @throws NotFoundException
   * @throws ConflictException
   * @throws CloudDriveAccessException
   */
  CmisObject updateObject(String parentId, String id, String name, LocalFile local) throws CMISException,
                                                                                   NotFoundException,
                                                                                   ConflictException,
                                                                                   CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject result = null;
      CmisObject obj;
      try {
        obj = session.getObject(id);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Object not found: " + id, e);
      }

      // update name
      if (!obj.getName().equals(name)) {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.NAME, name);

        // update object properties
        ObjectId objId = obj.updateProperties(properties, true);
        if (objId != null && objId instanceof CmisObject) {
          obj = result = (CmisObject) objId;
        }
      }

      if (isFileable(obj)) {
        FileableCmisObject fileable = (FileableCmisObject) obj;

        // update parent if required
        // go through actual parents to find should we move/add the file to another parent
        boolean move = true;
        List<Folder> parents = fileable.getParents();
        Set<String> parentIds = new HashSet<String>();
        for (Folder p : parents) {
          String pid = p.getId();
          parentIds.add(pid);
          if (pid.equals(parentId)) {
            move = false;
            break;
          }
        }
        if (move) {
          try {
            obj = session.getObject(parentId);
          } catch (CmisObjectNotFoundException e) {
            throw new NotFoundException("Parent not found: " + parentId, e);
          }
          if (isFolder(obj)) {
            Folder parent = (Folder) obj;
            Folder srcParent;
            if (parents.size() > 1) {
              // need lookup in local drive and compare with remote to find the srcParent
              String rpid = local.findRemoteParent(id, parentIds);
              if (rpid != null) {
                try {
                  obj = session.getObject(rpid);
                } catch (CmisObjectNotFoundException e) {
                  throw new NotFoundException("Source parent not found: " + rpid, e);
                }
                if (isFolder(obj)) {
                  srcParent = (Folder) obj;
                } else {
                  throw new CMISException("Source parent not a folder: " + rpid + ", " + obj.getName());
                }
              } else {
                // if all remote parents are local also,
                // we only can use multi-filing to add this document to the required parent
                if (session.getRepositoryInfo().getCapabilities().isMultifilingSupported()) {
                  fileable.addToFolder(parent, true);
                  return fileable; // and return from here
                } else {
                  throw new CMISException("Cannot move document without source folder and with disabled multi-filing: "
                      + id + ", " + name);
                }
              }
            } else {
              // obviously it's source parent
              srcParent = parents.get(0);
            }

            FileableCmisObject moved = fileable.move(srcParent, parent);
            if (moved != null) {
              result = moved;
            }
          } else {
            throw new CMISException("Parent not a folder: " + parentId + ", " + obj.getName());
          }
        }

        return result; // resulting object updated to reflect remote changes
      } else {
        throw new CMISException("Object not a document: " + id + ", " + obj.getName());
      }
    } catch (CmisUpdateConflictException e) {
      // conflict actual for update/deletion/move
      throw new ConflictException("Conflict of object updating for '" + name + "'", e);
    } catch (CmisObjectNotFoundException e) {
      // this can be a rice condition when parent just deleted or similar happened remotely
      throw new NotFoundException("Error updating object: " + e.getMessage(), e);
    } catch (CmisNameConstraintViolationException e) {
      // name constraint considered as conflict (requires another name)
      // TODO check cyclic loop not possible due to infinite error - change name - error - change...
      throw new ConflictException("Unable to update object with name '" + name
          + "' due to repository constraints", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new CMISException("Unable to update object '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error updating object: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISException("Error updating object: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for object updating: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // session credentials already checked in session() method, here is something else and we don't know
      // how to deal with it
      throw new CloudDriveAccessException("Unauthorized to update object: " + e.getMessage(), e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error updating object: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error updating object: " + e.getMessage(), e);
    }
  }

  /**
   * Update folder name or/and its parent. If folder was actually updated (name or/and
   * parent changed) this method return updated folder object or <code>null</code> if folder already exists
   * with such name and parent.
   * 
   * @param parentId {@link String}
   * @param id {@link String}
   * @param name {@link String}
   * @param local {@link LocalFile} access to local folder for move operation support
   * @return {@link Folder} of actually changed folder or <code>null</code> if folder already exists with
   *         such name and parent.
   * @throws CMISException
   * @throws NotFoundException
   * @throws ConflictException
   * @throws CloudDriveAccessException
   */
  Folder updateFolder(String parentId, String id, String name, LocalFile local) throws CMISException,
                                                                               NotFoundException,
                                                                               ConflictException,
                                                                               CloudDriveAccessException {
    CmisObject obj = updateObject(parentId, id, name, local);
    if (obj == null || isFolder(obj)) {
      return (Folder) obj;
    } else {
      throw new CMISException("Object not a folder: " + id + ", " + obj.getName());
    }
  }

  /**
   * Copy document to a new one. If file was successfully copied this method return new document object.
   * 
   * 
   * @param id {@link String}
   * @param parentId {@link String}
   * @param name {@link String}
   * @param modified {@link Calendar}
   * @return {@link Document} of actually copied file.
   * @throws CMISException
   * @throws NotFoundException
   * @throws ConflictException
   * @throws CloudDriveAccessException
   */
  Document copyDocument(String id, String parentId, String name) throws CMISException,
                                                                NotFoundException,
                                                                ConflictException,
                                                                CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = session.getObject(parentId);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Parent not found: " + parentId, e);
      }
      if (isFolder(obj)) {
        Folder parent = (Folder) obj;
        try {
          obj = session.getObject(id);
        } catch (CmisObjectNotFoundException e) {
          throw new NotFoundException("Source not found: " + parentId, e);
        }
        if (isDocument(obj)) {
          return copyDocument((Document) obj, parent, name);
        } else {
          throw new CMISException("Source not a document: " + id + ", " + obj.getName());
        }
      } else {
        throw new CMISException("Parent not a folder: " + parentId + ", " + obj.getName());
      }
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    }
  }

  /**
   * Copy document to a new one using CMIS objects. If file was successfully copied this method return new
   * document object.
   * 
   * @param source {@link Document}
   * @param parent {@link Folder}
   * @param name {@link String}
   * @param modified {@link Calendar}
   * @return {@link Document} of actually copied file.
   * @throws CMISException
   * @throws NotFoundException
   * @throws ConflictException
   * @throws CloudDriveAccessException
   */
  Document copyDocument(Document source, Folder parent, String name) throws CMISException,
                                                                    NotFoundException,
                                                                    ConflictException,
                                                                    CloudDriveAccessException {
    try {
      Map<String, Object> properties = new HashMap<String, Object>();
      properties.put(PropertyIds.OBJECT_TYPE_ID, source.getBaseTypeId().value());
      properties.put(PropertyIds.NAME, name);
      return parent.createDocumentFromSource(source, properties, VersioningState.MAJOR);
    } catch (CmisObjectNotFoundException e) {
      // this can be a rice condition when parent just deleted or similar happened remotely
      throw new NotFoundException("Error copying document: " + e.getMessage(), e);
    } catch (CmisNameConstraintViolationException e) {
      // name constraint considered as conflict (requires another name)
      // TODO check cyclic loop not possible due to infinite error - change name - error - change...
      throw new ConflictException("Unable to copy document with name '" + name
          + "' due to repository constraints", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new CMISException("Unable to copy document '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for document content copying: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document copying: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // session credentials already checked in session() method, here is something else and we don't know
      // how to deal with it
      throw new CloudDriveAccessException("Unauthorized for copying document: " + e.getMessage(), e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    }
  }

  /**
   * Copy folder to a new one. If folder was successfully copied this method return new folder object. Notable
   * that CMIS doesn't support folder copying natively and this method does recursive traversing of the source
   * and create new folder
   * with sub-folders from it. This may cause not full copy of a folder in general
   * case (permissions or other metadata may not be copied).
   * 
   * @param id {@link String}
   * @param parentId {@link String}
   * @param name {@link String}
   * @return {@link Folder} of actually copied folder.
   * @throws CMISException
   * @throws NotFoundException
   * @throws ConflictException
   * @throws CloudDriveAccessException
   * 
   * @see #copyFolder(Folder, Folder, String)
   */
  Folder copyFolder(String id, String parentId, String name) throws CMISException,
                                                            NotFoundException,
                                                            ConflictException,
                                                            CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = session.getObject(parentId);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Parent not found: " + parentId, e);
      }
      if (isFolder(obj)) {
        Folder parent = (Folder) obj;
        try {
          obj = session.getObject(id);
        } catch (CmisObjectNotFoundException e) {
          throw new NotFoundException("Source not found: " + parentId, e);
        }
        if (isFolder(obj)) {
          Folder source = (Folder) obj;
          return copyFolder(source, parent, name);
        } else {
          throw new CMISException("Source not a folder: " + id + ", " + obj.getName());
        }
      } else {
        throw new CMISException("Parent not a folder: " + parentId + ", " + obj.getName());
      }
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    }
  }

  /**
   * Copy folder to a new one using CMIS objects. If folder was successfully copied this method return new
   * folder object. <br>
   * Notable that CMIS doesn't support folder copy and this method does traversing of the source and create
   * new folder with sub-folders and only copy documents from them. This may cause not full copy of a folder
   * in general case (permissions or other metadata may not be copied).
   * 
   * @param id {@link String}
   * @param parentId {@link String}
   * @param name {@link String}
   * @return {@link Folder} of actually copied folder.
   * @throws CMISException
   * @throws NotFoundException
   * @throws ConflictException
   * @throws CloudDriveAccessException
   */
  Folder copyFolder(Folder source, Folder parent, String name) throws CMISException,
                                                              NotFoundException,
                                                              ConflictException,
                                                              CloudDriveAccessException {
    try {
      Map<String, Object> properties = new HashMap<String, Object>(2);
      properties.put(PropertyIds.NAME, source.getName());
      properties.put(PropertyIds.OBJECT_TYPE_ID, source.getBaseTypeId().value());
      Folder copyFolder = parent.createFolder(properties);
      // copy child documents recursively
      for (CmisObject child : source.getChildren()) {
        if (isDocument(child)) {
          copyDocument((Document) child, parent, child.getName());
        } else if (child instanceof Folder) {
          copyFolder((Folder) child, parent, child.getName());
        }
      }
      return copyFolder;
    } catch (CmisObjectNotFoundException e) {
      // this can be a rice condition when parent just deleted or similar happened remotely
      throw new NotFoundException("Error copying document: " + e.getMessage(), e);
    } catch (CmisNameConstraintViolationException e) {
      // name constraint considered as conflict (requires another name)
      // TODO check cyclic loop not possible due to infinite error - change name - error - change...
      throw new ConflictException("Unable to copy document with name '" + name
          + "' due to repository constraints", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new CMISException("Unable to copy document '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for document content copying: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document copying: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // session credentials already checked in session() method, here is something else and we don't know
      // how to deal with it
      throw new CloudDriveAccessException("Unauthorized for copying document: " + e.getMessage(), e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    }
  }

  RepositoryInfo getRepositoryInfo() throws CMISException, RefreshAccessException {
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

  boolean isFileable(CmisObject object) {
    if (object instanceof FileableCmisObject) {
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
   * @throws CMISException when runtime or connection error happens
   * @throws RefreshAccessException if user credentials rejected (and need try renew them)
   */
  private List<Repository> repositories() throws CMISException, RefreshAccessException {
    try {
      lock.lock();
      SessionFactory sessionFactory = SessionFactoryImpl.newInstance();
      return sessionFactory.getRepositories(parameters);
    } catch (CmisConnectionException e) {
      // The server is unreachable
      throw new CMISException("CMIS server is unreachable", e);
    } catch (CmisUnauthorizedException e) {
      // The user/password have probably been rejected by the server.
      throw new RefreshAccessException("CMIS user rejected", e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error reading CMIS repositories list", e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Create CMIS session.
   * 
   * @return {@link Session}
   * @throws CMISException
   * @throws RefreshAccessException
   */
  private Session session() throws CMISException, RefreshAccessException {
    Session session = localSession.get();
    if (session != null) {
      // TODO should we check if session still live (not closed)?
      return session;
    } else {
      for (Repository r : repositories()) {
        if (r.getId().equals(repositoryId)) {
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
