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
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.bindings.CmisBindingFactory;
import org.apache.chemistry.opencmis.client.bindings.spi.LinkAccess;
import org.apache.chemistry.opencmis.client.runtime.OperationContextImpl;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
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
import java.util.NoSuchElementException;
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
  protected class ChildrenIterator extends ChunkIterator<CmisObject> {
    protected final String folderId;

    /**
     * Parent folder.
     */
    protected Folder       parent;

    protected ChildrenIterator(String folderId) throws CloudDriveException {
      this.folderId = folderId;

      // fetch first
      this.iter = nextChunk();
    }

    protected Iterator<CmisObject> nextChunk() throws CloudDriveException {
      try {
        CmisObject obj = readObject(folderId, session(), objectContext);
        if (isFolder(obj)) {
          // it is folder
          parent = (Folder) obj;
          ItemIterable<CmisObject> children = parent.getChildren(folderContext);
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
  protected class ChangesIterator extends ChunkIterator<ChangeEvent> {

    protected String            startChangeToken, changeToken;

    protected List<ChangeEvent> changes;

    // TODO cleanup: ChangeEvents events;

    protected ChangesIterator(String startChangeToken) throws CMISException, RefreshAccessException {
      this.startChangeToken = changeToken = startChangeToken;

      // fetch first
      this.iter = nextChunk();
    }

    protected Iterator<ChangeEvent> nextChunk() throws CMISException, RefreshAccessException {
      try {
        // XXX includeProperties = false, maxNumItems = max possible value to fetch all at once
        // TODO better pagination organization

        // session(true).getContentChanges(changeToken,
        // true).iterator().hasNext()
        ChangeEvents events = session().getContentChanges(changeToken, true, Long.MAX_VALUE);

        changes = events.getChangeEvents();

        // TODO remember position for next chunk and next iterators
        int changesLen = changes.size();
        String latestChangeToken = events.getLatestChangeLogToken();
        if (latestChangeToken == null && changesLen > 0) {
          latestChangeToken = changes.get(changesLen - 1).getProperties().get("ChangeToken").toString();
        }
        changeToken = latestChangeToken;

        // TODO accurate available number
        // long total = events.getTotalNumItems();
        // if (total == -1) {
        // total = changes.size();
        // }
        available(changesLen);

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
      // TODO SP return true even if nothing new there: return events.getHasMoreItems();
      return !startChangeToken.equals(changeToken) && changeToken != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() throws CloudDriveException {
      boolean hasNext = super.hasNext();
      // avoid appearing UPDATED of later DELETED objects
      if (hasNext && !ChangeType.DELETED.equals(next.getChangeType())) {
        for (ChangeEvent che : changes) {
          if (next.getObjectId().equals(che.getObjectId()) && ChangeType.DELETED.equals(che.getChangeType())) {
            try {
              // skip this UPDATED as it was DELETED in this changes set
              next();
              hasNext = hasNext();
            } catch (NoSuchElementException e) {
              hasNext = false;
            }
            break;
          }
        }
      }
      return hasNext;
    }

    /**
     * Can be <code>null</code>. And it will be :)
     * 
     * @return
     */
    protected String getLatestChangeLogToken() {
      return changeToken;
    }
  }

  /**
   * Sample drive state POJO.
   */
  public static class DriveState {
    protected final String type;

    protected final String url;

    protected final long   retryTimeout, created;

    protected DriveState(String type, String url, long retryTimeout) {
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
   * Session context for CMIS calls. Class idea wrapped from OpenCMIS Workbench.
   */
  protected class Context extends OperationContextImpl {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor of CMIS context;
     * 
     * @param filter
     * @param includeAcls
     * @param includeAllowableActions
     * @param includePolicies
     * @param includeRelationships
     * @param renditionFilter
     * @param orderBy
     * @param maxItemsPerPage
     */
    protected Context(String filter,
                      boolean includeAcls,
                      boolean includeAllowableActions,
                      boolean includePolicies,
                      IncludeRelationships includeRelationships,
                      String renditionFilter,
                      String orderBy,
                      int maxItemsPerPage) {
      setFilterString(filter);
      setIncludeAcls(includeAcls);
      setIncludeAllowableActions(includeAllowableActions);
      setIncludePolicies(includePolicies);
      setIncludeRelationships(includeRelationships);
      setRenditionFilterString(renditionFilter);
      setOrderBy(orderBy);
      setMaxItemsPerPage(maxItemsPerPage);

      setIncludePathSegments(false);
      setCacheEnabled(false);
    }
  }

  /**
   * Client session lock.
   */
  private final Lock                 lock                = new ReentrantLock();

  /**
   * OpenCMIS context for object operations.
   */
  protected OperationContext         objectContext;

  /**
   * OpenCMIS context for folder operations.
   */
  protected OperationContext         folderContext;

  protected static final Set<String> FOLDER_PROPERTY_SET = new HashSet<String>();
  static {
    FOLDER_PROPERTY_SET.add(PropertyIds.OBJECT_ID);
    FOLDER_PROPERTY_SET.add(PropertyIds.OBJECT_TYPE_ID);
    FOLDER_PROPERTY_SET.add(PropertyIds.NAME);
    FOLDER_PROPERTY_SET.add(PropertyIds.CONTENT_STREAM_MIME_TYPE);
    FOLDER_PROPERTY_SET.add(PropertyIds.CONTENT_STREAM_LENGTH);
    FOLDER_PROPERTY_SET.add(PropertyIds.CONTENT_STREAM_FILE_NAME);
    FOLDER_PROPERTY_SET.add(PropertyIds.CREATED_BY);
    FOLDER_PROPERTY_SET.add(PropertyIds.CREATION_DATE);
    FOLDER_PROPERTY_SET.add(PropertyIds.LAST_MODIFIED_BY);
    FOLDER_PROPERTY_SET.add(PropertyIds.LAST_MODIFICATION_DATE);
    FOLDER_PROPERTY_SET.add(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT);
    FOLDER_PROPERTY_SET.add(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID);
  }

  /**
   * Client session parameters.
   */
  protected Map<String, String>      parameters;

  /**
   * Current CMIS repository Id.
   */
  protected String                   repositoryId;

  /**
   * Current CMIS repository name;
   */
  protected String                   repositoryName;

  protected DriveState               state;

  protected String                   enterpriseId, enterpriseName, customDomain;

  protected ThreadLocal<Session>     localSession        = new ThreadLocal<Session>();

  /**
   * Create API from user credentials.
   * 
   * @param serviceURL {@link String}
   * @param user {@link String}
   * @param password {@link String}
   * @throws CMISException
   * @throws CloudDriveException
   */
  protected CMISAPI(String serviceURL, String user, String password) throws CMISException,
      CloudDriveException {
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

    // init drive state
    updateState();
  }

  /**
   * Update user credentials.
   * 
   * @param user {@link String}
   * @param password {@link String}
   * @throws CloudDriveException
   */
  protected void updateUser(Map<String, String> parameters) {
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
  protected Map<String, String> getParamaters() {
    try {
      lock.lock();
      return Collections.unmodifiableMap(parameters);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Currently connected cloud user name.
   * 
   * @return String
   * @throws CMISException
   * @throws RefreshAccessException
   */
  protected String getUser() {
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
  protected String getPassword() {
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
  protected String getServiceURL() {
    try {
      lock.lock();
      return parameters.get(SessionParameter.ATOMPUB_URL);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Init current CMIS repository for late use.
   * 
   * @param repositoryId {@link String} repository name
   * @throws CMISException
   * @throws RefreshAccessException
   */
  protected void initRepository(String repositoryId) throws CMISException, RefreshAccessException {
    this.repositoryId = repositoryId;
    this.repositoryName = getRepositoryInfo().getName();
  }

  /**
   * Current CMIS repository.
   * 
   * @return the repository
   */
  protected String getRepositoryId() {
    return repositoryId;
  }

  protected String getRepositoryName() {
    return repositoryName != null ? repositoryName : repositoryId;
  }

  protected String getUserTitle() {
    // By default CMIS cannot provide something else than an username
    // Vendor specific APIes may override this method to return proper value
    return getUser();
  }

  /**
   * Available repositories for current user.
   * 
   * @return list of {@link Repository} objects
   * @throws CMISException
   * @throws RefreshAccessException
   */
  protected List<Repository> getRepositories() throws CMISException, RefreshAccessException {
    return Collections.unmodifiableList(repositories());
  }

  /**
   * The drive root folder.
   * 
   * @return {@link Folder}
   * @throws CMISException
   * @throws RefreshAccessException
   */
  protected Folder getRootFolder() throws CMISException, RefreshAccessException {
    try {
      Folder root = session().getRootFolder();
      return root;
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error getting root folder: " + e.getMessage(), e);
    }
  }

  /**
   * For internal use. This method doesn't handle OpenCMIS exceptions.
   * 
   * @param id {@link String}
   * @param session {@link Session}
   * @param context {@link OperationContext}
   * @return {@link CmisObject}
   */
  protected CmisObject readObject(String id, Session session, OperationContext context) {
    return session.getObject(id, context);
  }

  /**
   * Return CMIS object from the repository.
   * 
   * @param objectId {@link String}
   * @return {@link CmisObject}
   * @throws CMISException
   * @throws NotFoundException
   * @throws CloudDriveAccessException
   */
  protected CmisObject getObject(String objectId) throws CMISException,
                                                 NotFoundException,
                                                 CloudDriveAccessException {
    try {
      CmisObject object = readObject(objectId, session(), objectContext);
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

  protected ChildrenIterator getFolderItems(String folderId) throws CloudDriveException {
    return new ChildrenIterator(folderId);
  }

  protected ChangesIterator getChanges(String changeToken) throws CMISException, RefreshAccessException {
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
  protected String getLink(CmisObject file) throws CMISException, RefreshAccessException {
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
  protected String getLink(Folder file) throws CMISException, RefreshAccessException {
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
  protected String getEmbedLink(CmisObject item) throws CMISException, RefreshAccessException {
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
  protected String getEmbedLink(Folder folder) throws CMISException, RefreshAccessException {
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
  protected String getEmbedLink(Document doc) throws CMISException, RefreshAccessException {
    return getLink(doc);
  }

  protected DriveState getState() throws CMISException, RefreshAccessException {
    // TODO state for CMIS?
    // if (state == null || state.isOutdated()) {
    // updateState();
    // }

    return null;
  }

  /**
   * Update the drive state.
   * 
   */
  protected void updateState() throws CMISException, RefreshAccessException {
    try {
      // TODO state for CMIS drive?
      this.state = null; // new DriveState("type...", "http://....", 10);
    } catch (Exception e) {
      throw new CMISException("Error getting drive state: " + e.getMessage(), e);
    }
  }

  protected Document createDocument(String parentId, String name, String mimeType, InputStream data) throws CMISException,
                                                                                                    NotFoundException,
                                                                                                    ConflictException,
                                                                                                    CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = readObject(parentId, session, objectContext);
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
        return parent.createDocument(properties,
                                     contentStream,
                                     VersioningState.MAJOR,
                                     null,
                                     null,
                                     null,
                                     objectContext);
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

  protected Folder createFolder(String parentId, String name) throws CMISException,
                                                             NotFoundException,
                                                             ConflictException,
                                                             CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = readObject(parentId, session, objectContext);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Parent not found: " + parentId, e);
      }
      if (isFolder(obj)) {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.OBJECT_TYPE_ID, BaseTypeId.CMIS_FOLDER.value());
        properties.put(PropertyIds.NAME, name);
        // created date not used as CMIS will set its own one
        Folder parent = (Folder) obj;
        return parent.createFolder(properties, null, null, null, folderContext);
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
  protected void deleteDocument(String id) throws CMISException,
                                          NotFoundException,
                                          ConflictException,
                                          CloudDriveAccessException {
    Session session = session();
    String name = "";
    try {
      CmisObject obj = readObject(id, session, objectContext);
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
  protected void deleteFolder(String id) throws CMISException,
                                        NotFoundException,
                                        ConflictException,
                                        CloudDriveAccessException {
    Session session = session();
    String name = "";
    try {
      CmisObject obj = readObject(id, session, objectContext);
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
  protected Document updateContent(String id, String name, InputStream data, String mimeType, LocalFile local) throws CMISException,
                                                                                                              NotFoundException,
                                                                                                              ConflictException,
                                                                                                              CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = readObject(id, session, objectContext);
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
  protected CmisObject updateObject(String parentId, String id, String name, LocalFile local) throws CMISException,
                                                                                             NotFoundException,
                                                                                             ConflictException,
                                                                                             CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject result = null;
      CmisObject obj;
      try {
        obj = readObject(id, session, objectContext);
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
            obj = readObject(parentId, session, objectContext);
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
                  obj = readObject(rpid, session, objectContext);
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

            FileableCmisObject moved = fileable.move(srcParent, parent, objectContext);
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
  protected Folder updateFolder(String parentId, String id, String name, LocalFile local) throws CMISException,
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
  protected Document copyDocument(String id, String parentId, String name) throws CMISException,
                                                                          NotFoundException,
                                                                          ConflictException,
                                                                          CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = readObject(parentId, session, objectContext);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Parent not found: " + parentId, e);
      }
      if (isFolder(obj)) {
        Folder parent = (Folder) obj;
        try {
          obj = readObject(id, session, objectContext);
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
  protected Document copyDocument(Document source, Folder parent, String name) throws CMISException,
                                                                              NotFoundException,
                                                                              ConflictException,
                                                                              CloudDriveAccessException {
    try {
      Map<String, Object> properties = new HashMap<String, Object>();
      properties.put(PropertyIds.OBJECT_TYPE_ID, source.getBaseTypeId().value());
      properties.put(PropertyIds.NAME, name);
      return parent.createDocumentFromSource(source,
                                             properties,
                                             VersioningState.MAJOR,
                                             null,
                                             null,
                                             null,
                                             objectContext);
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
  protected Folder copyFolder(String id, String parentId, String name) throws CMISException,
                                                                      NotFoundException,
                                                                      ConflictException,
                                                                      CloudDriveAccessException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = readObject(parentId, session, objectContext);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Parent not found: " + parentId, e);
      }
      if (isFolder(obj)) {
        Folder parent = (Folder) obj;
        try {
          obj = readObject(id, session, objectContext);
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
  protected Folder copyFolder(Folder source, Folder parent, String name) throws CMISException,
                                                                        NotFoundException,
                                                                        ConflictException,
                                                                        CloudDriveAccessException {
    try {
      Map<String, Object> properties = new HashMap<String, Object>(2);
      properties.put(PropertyIds.NAME, source.getName());
      properties.put(PropertyIds.OBJECT_TYPE_ID, source.getBaseTypeId().value());
      Folder copyFolder = parent.createFolder(properties, null, null, null, folderContext);
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

  protected RepositoryInfo getRepositoryInfo() throws CMISException, RefreshAccessException {
    try {
      return session(true).getRepositoryInfo();
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error getting repository info: " + e.getMessage(), e);
    }
  }

  protected boolean isFolder(CmisObject object) {
    if (object.getBaseTypeId().equals(BaseTypeId.CMIS_FOLDER)) {
      return true;
    }
    return false;
  }

  protected boolean isDocument(CmisObject object) {
    if (object.getBaseTypeId().equals(BaseTypeId.CMIS_DOCUMENT)) {
      return true;
    }
    return false;
  }

  protected boolean isRelationship(CmisObject object) {
    if (object.getBaseTypeId().equals(BaseTypeId.CMIS_RELATIONSHIP)) {
      return true;
    }
    return false;
  }

  protected boolean isFileable(CmisObject object) {
    if (object instanceof FileableCmisObject) {
      return true;
    }
    return false;
  }

  /**
   * List of repositories available on CMIS service.
   * 
   * @return list of {@link Repository} objects
   * @throws CMISException when runtime or connection error happens
   * @throws RefreshAccessException if user credentials rejected (and need try renew them)
   */
  protected List<Repository> repositories() throws CMISException, RefreshAccessException {
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
   * Create CMIS binding instance (low-level API but with fine grained control).<br>
   * 
   * @return {@link CmisBinding}
   * @throws CMISException
   */
  protected CmisBinding binding() throws CMISException {
    CmisBindingFactory factory = CmisBindingFactory.newInstance();

    Map<String, String> sessionParameters = new HashMap<String, String>(parameters);
    if (repositoryId != null) {
      sessionParameters.put(SessionParameter.REPOSITORY_ID, repositoryId);
    }

    return factory.createCmisAtomPubBinding(parameters);
  }

  /**
   * Create CMIS session.
   * 
   * @return {@link Session}
   * @throws CMISException
   * @throws RefreshAccessException
   */
  protected Session session() throws CMISException, RefreshAccessException {
    return session(false);
  }

  /**
   * Create CMIS session.
   * 
   * @param forceNew boolean if <code>true</code> then session will be recreated, otherwise will try use
   *          cached in thread-local variable.
   * @return {@link Session}
   * @throws CMISException
   * @throws RefreshAccessException
   */
  protected Session session(boolean forceNew) throws CMISException, RefreshAccessException {
    Session session = localSession.get();
    if (session != null && !forceNew) {
      // TODO should we check if session still live (not closed)?
      return session;
    } else {
      for (Repository r : repositories()) {
        if (r.getId().equals(repositoryId)) {
          session = r.createSession();

          // default context
          OperationContext context = session.createOperationContext();
          context.setCacheEnabled(false);
          session.setDefaultContext(context);

          // object/document context
          this.objectContext = new Context("*", false, true, true, IncludeRelationships.BOTH, "*", null, 1000);

          // folder context
          ObjectType type = session.getTypeDefinition(BaseTypeId.CMIS_DOCUMENT.value());
          StringBuilder filter = new StringBuilder();
          for (String propId : FOLDER_PROPERTY_SET) {
            PropertyDefinition<?> propDef = type.getPropertyDefinitions().get(propId);
            if (propDef != null) {
              if (filter.length() > 0) {
                filter.append(',');
              }
              filter.append(propDef.getQueryName());
            }
          }
          this.folderContext = new Context(filter.toString(),
                                           false,
                                           false,
                                           false,
                                           IncludeRelationships.NONE,
                                           "cmis:none",
                                           null,
                                           Integer.MAX_VALUE); // TODO max pages = 10000 (as in workbench)

          localSession.set(session);
          return session;
        }
      }
    }
    throw new CMISException("CMIS repository not found: " + repositoryId);
  }

  protected OperationContext objectContext() throws CMISException, RefreshAccessException {
    return objectContext != null ? objectContext : session().getDefaultContext();
  }

  protected OperationContext folderContext() throws CMISException, RefreshAccessException {
    return folderContext != null ? folderContext : session().getDefaultContext();
  }

}
