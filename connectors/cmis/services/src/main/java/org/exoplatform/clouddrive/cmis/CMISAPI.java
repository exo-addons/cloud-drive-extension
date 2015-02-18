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

import org.apache.chemistry.opencmis.client.SessionParameterMap;
import org.apache.chemistry.opencmis.client.api.ChangeEvent;
import org.apache.chemistry.opencmis.client.api.ChangeEvents;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.DocumentType;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Property;
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
import org.apache.chemistry.opencmis.commons.enums.CapabilityAcl;
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
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
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
import org.exoplatform.clouddrive.ConstraintException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.UnauthorizedException;
import org.exoplatform.clouddrive.cmis.JCRLocalCMISDrive.LocalFile;
import org.exoplatform.clouddrive.utils.ChunkIterator;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * All calls to CMIS API here.
 * 
 */
public class CMISAPI {

  protected static final Log LOG                   = ExoLogger.getLogger(CMISAPI.class);

  public static final String NO_STATE              = "__no_state_set__";

  /**
   * Page size used by object context.
   */
  public static final int    OBJECT_PAGE_SIZE      = 1024;

  /**
   * Page size used by folder context and children/change iterators for large data sets.
   */
  public static final int    FOLDER_PAGE_SIZE      = 10240;

  public static final String TOKEN_DATATIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

  public static final String EMPTY_TOKEN           = "".intern();

  /**
   * Iterator over whole set of items from cloud service. This iterator hides next-chunk logic on
   * request to the service. <br>
   * Iterator methods can throw {@link CloudDriveException} in case of remote or communication errors.
   * 
   */
  protected class ChildrenIterator extends ChunkIterator<CmisObject> {

    protected final String             folderId;

    /**
     * Parent folder.
     */
    protected Folder                   parent;

    /**
     * Parent's children objects.
     */
    protected ItemIterable<CmisObject> children;

    protected ChildrenIterator(String folderId) throws CloudDriveException {
      this.folderId = folderId;

      // fetch first
      this.iter = nextChunk();
    }

    protected Iterator<CmisObject> nextChunk() throws CloudDriveException {
      try {
        CmisObject obj = readObject(folderId, session(), folderContext);
        if (isFolder(obj)) {
          // it is folder
          parent = (Folder) obj;
          children = parent.getChildren(fileContext);
          long total = children.getTotalNumItems();
          if (total == -1) {
            total = children.getPageNumItems();
          }
          available(total);
          return children.iterator();
        } else {
          // empty iterator
          return new ArrayList<CmisObject>().iterator();
        }
      } catch (CmisConnectionException e) {
        // communication (REST) error
        throw new CMISException("Error getting folder items: " + e.getMessage(), e);
      } catch (CmisInvalidArgumentException e) {
        // wrong input data: use dedicated exception type to let upper code to recognize it
        throw new CMISInvalidArgumentException("Error getting folder items (parent not a folder): "
            + e.getMessage(), e);
      } catch (CmisPermissionDeniedException e) {
        throw new RefreshAccessException("Permission denied for getting folder items: " + e.getMessage(), e);
      } catch (CmisUnauthorizedException e) {
        // TODO UnauthorizedException not a case here?
        throw new CloudDriveAccessException("Unauthorized for getting folder items: " + e.getMessage(), e);
      } catch (CmisRuntimeException e) {
        throw new CMISException("Error getting folder items: " + e.getMessage(), e);
      } catch (CmisBaseException e) {
        throw new CMISException("Error getting folder items: " + e.getMessage(), e);
      }
    }

    protected boolean hasNextChunk() {
      // pagination via chunks not actual here - it is done by OpenCMIS in parent.getChildren() and its
      // CollectionIterator
      return false;
    }
  }

  /**
   * Iterator over set of drive change events from CMIS Change Log. The iterator will fetch all available
   * events since a given start token.This iterator hides next-chunk logic on
   * request to the service. <br>
   * Iterator methods can throw {@link CMISException} in case of remote or communication errors.
   */
  protected class ChangesIterator extends ChunkIterator<ChangeEvent> {

    protected ChangeToken       changeToken, lastFetchedToken, latestChunkToken;

    protected List<ChangeEvent> changes;

    protected boolean           firstRun     = true;

    protected boolean           hasMoreItems = true;

    protected boolean           cleanNext    = true;

    protected ChangesIterator(ChangeToken startChangeToken) throws CMISException, CloudDriveAccessException {
      this.changeToken = startChangeToken;
      this.lastFetchedToken = latestChunkToken = emptyToken();

      // fetch first
      this.iter = nextChunk();

      this.firstRun = false;
    }

    protected Iterator<ChangeEvent> nextChunk() throws CMISException, CloudDriveAccessException {
      if (!changeToken.isEmpty()) {
        try {
          ChangeEvents events = session().getContentChanges(changeToken.getString(), true, FOLDER_PAGE_SIZE);

          changes = events.getChangeEvents();

          // latest token can be empty (null) for some CMIS impl (e.g. SP)
          // latestChunkToken = latestChangeToken != null ? readToken(latestChangeToken) : null;
          latestChunkToken = readToken(events.getLatestChangeLogToken());

          int changesLen = changes.size();

          // find need of next chunk fetching (have more events in CMIS changes log) and a token for it
          if (changesLen > 0) {
            // remove first, already fetched in previous sync/chunk
            ChangeToken first = readToken(changes.get(0));
            if (first.equals(changeToken) || first.equals(lastFetchedToken)) {
              changes.remove(0);
              changesLen = changes.size();
            }

            if (events.getHasMoreItems() && changesLen > 0) {
              ChangeToken nextToken;
              if (latestChunkToken.isEmpty()) {
                nextToken = readToken(changes.get(changesLen - 1));
              } else {
                nextToken = latestChunkToken;
              }
              hasMoreItems = lastFetchedToken.isEmpty() ? true : nextToken.isAfter(lastFetchedToken);
              changeToken = hasMoreItems ? nextToken : null;
            } else {
              hasMoreItems = false;
              changeToken = emptyToken();
            }
          } else {
            hasMoreItems = false;
            changeToken = emptyToken();
          }

          available(changesLen);

          return changes.iterator();

        } catch (CmisConnectionException e) {
          // communication (REST) error
          throw new CMISException("Error getting remote changes: " + e.getMessage(), e);
        } catch (CmisPermissionDeniedException e) {
          throw new RefreshAccessException("Permission denied for getting folder items: " + e.getMessage(), e);
        } catch (CmisUnauthorizedException e) {
          // TODO UnauthorizedException not a case here?
          throw new CloudDriveAccessException("Unauthorized for getting remote changes: " + e.getMessage(), e);
        } catch (CmisConstraintException e) {
          // CMIS 1.0 - The Repository MUST throw this exception if the event corresponding to the change
          // log token provided as an input parameter is no longer available in the change log. (E.g.
          // because the change log was truncated).
          throw new CMISInvalidArgumentException("Error getting remote changes (event corresponding "
              + "to provided change log token is no longer available): " + e.getMessage(), e);
        } catch (CmisInvalidArgumentException e) {
          // CMIS v1.1 - if the event corresponding to the change log token provided as an input parameter
          // is no longer available in the change log. (E.g. because the change log was truncated).
          throw new CMISInvalidArgumentException("Error getting remote changes (event corresponding "
              + "to provided change log token is no longer available): " + e.getMessage(), e);
        } catch (CmisRuntimeException e) {
          throw new CMISException("Error getting remote changes: " + e.getMessage(), e);
        }
      } else {
        return new ArrayList<ChangeEvent>().iterator(); // empty
      }
    }

    /**
     * {@inheritDoc}
     */
    protected boolean hasNextChunk() {
      return hasMoreItems;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() throws CloudDriveException {
      boolean hasNext = super.hasNext();
      // FYI not mandatory, but helpful for performance reason: avoid appearing events of DELETED objects
      if (hasNext && cleanNext) {
        lastFetchedToken = readToken(next);
        if (!ChangeType.DELETED.equals(next.getChangeType())) {
          for (ChangeEvent che : changes) {
            if (next.getObjectId().equals(che.getObjectId())
                && ChangeType.DELETED.equals(che.getChangeType())) {
              try {
                // skip this event as it was DELETED in this changes set
                next();
                hasNext = hasNext();
              } catch (NoSuchElementException e) {
                hasNext = false;
              }
              break;
            }
          }
        }
        // avoids not required looping if hasNext() called several times without calling next()
        cleanNext = false;
      }

      return hasNext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChangeEvent next() throws NoSuchElementException, CloudDriveException {
      ChangeEvent next = super.next();
      cleanNext = true;
      return next;
    }

    /**
     * Last consumed event change token. Can be the same as start token. Can be <code>null</code> if no data
     * received by fetching and if CMIS service doesn't return the chunk latest token.
     * 
     * @return {@link ChangeToken} last fetched change token, token of last fetched events portion or
     *         <code>null</code>
     */
    protected ChangeToken getLastChangeToken() {
      // first priority in actually fetched token, then if nothing fetched we'll try a token from last fetched
      // chunk, and if not available, return last used for fetching token (it can be null also)
      return !lastFetchedToken.isEmpty() ? lastFetchedToken : (!latestChunkToken.isEmpty() ? latestChunkToken
                                                                                          : changeToken);
    }
  }

  protected class ChangeToken {
    protected final String token;

    protected final Long   order;

    protected ChangeToken(String token) {
      if (token == null) {
        this.token = EMPTY_TOKEN;
        this.order = null;
      } else {
        this.token = token;
        Long order;
        try {
          order = Long.parseLong(token);
        } catch (NumberFormatException e) {
          order = null;
        }
        this.order = order;
      }
    }

    /**
     * Compare this token with the given and return <code>true</code> if they are equal, <code>false</code>
     * otherwise. Empty tokens aren't equal.
     * 
     * @param other {@link ChangeToken}
     * @return boolean <code>true</code> if tokens equal, <code>false</code> otherwise
     */
    public boolean equals(ChangeToken other) {
      if (other != null && !isEmpty() && !other.isEmpty()) {
        return this.getString().equals(other.getString());
      } else {
        return false;
      }
    }

    /**
     * Return <code>true</code> if this event is after the given in time.
     * 
     * @param other {@link ChangeToken}
     * @return boolean
     */
    public boolean isAfter(ChangeToken other) {
      return !isEmpty() && this.compareTo(other) > 0;
    }

    /**
     * Return <code>true</code> if this event is before the given in time.
     * 
     * @param other {@link ChangeToken}
     * @return boolean
     */
    public boolean isBefore(ChangeToken other) {
      return !isEmpty() && this.compareTo(other) < 0;
    }

    /**
     * Return <code>true</code> if this token doesn't describe any change token, thus it equals no
     * <code>null</code> or no token value.
     * 
     * @return boolean
     */
    public boolean isEmpty() {
      return token.equals(EMPTY_TOKEN);
    }

    /**
     * String representation of change log token.
     * 
     * @return the token string
     */
    public String getString() {
      return token;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      return getString();
    }

    protected int compareTo(ChangeToken other) {
      if (order != null && other.order != null) {
        return order.compareTo(other.order);
      } else {
        // XXX it is enough dangerous comparison, better override in vendor specific API
        return this.getString().compareTo(other.getString());
      }
    }
  }

  protected class TimeChangeToken extends ChangeToken {

    protected final GregorianCalendar time;

    protected TimeChangeToken(GregorianCalendar time) {
      super(String.valueOf(time.getTimeInMillis()));
      this.time = time;
    }

    /**
     * @return the time
     */
    protected GregorianCalendar getTime() {
      return time;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(ChangeToken other) {
      if (other instanceof TimeChangeToken) {
        return this.getTime().equals(((TimeChangeToken) other).getTime());
      }
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(ChangeToken other) {
      if (other instanceof TimeChangeToken) {
        return this.getTime().compareTo(((TimeChangeToken) other).getTime());
      }
      return super.compareTo(other);
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
   * Set of properties for CMIS files (document or folder).
   */
  protected static final Set<String>       FILE_PROPERTY_SET    = new LinkedHashSet<String>();
  static {
    FILE_PROPERTY_SET.add(PropertyIds.OBJECT_ID);
    FILE_PROPERTY_SET.add(PropertyIds.PARENT_ID);
    FILE_PROPERTY_SET.add(PropertyIds.BASE_TYPE_ID);
    FILE_PROPERTY_SET.add(PropertyIds.OBJECT_TYPE_ID);
    FILE_PROPERTY_SET.add(PropertyIds.NAME);
    FILE_PROPERTY_SET.add(PropertyIds.IS_IMMUTABLE);
    FILE_PROPERTY_SET.add(PropertyIds.CONTENT_STREAM_MIME_TYPE);
    FILE_PROPERTY_SET.add(PropertyIds.CONTENT_STREAM_LENGTH);
    FILE_PROPERTY_SET.add(PropertyIds.CONTENT_STREAM_FILE_NAME);
    FILE_PROPERTY_SET.add(PropertyIds.CREATED_BY);
    FILE_PROPERTY_SET.add(PropertyIds.CREATION_DATE);
    FILE_PROPERTY_SET.add(PropertyIds.LAST_MODIFIED_BY);
    FILE_PROPERTY_SET.add(PropertyIds.LAST_MODIFICATION_DATE);
    FILE_PROPERTY_SET.add(PropertyIds.CHANGE_TOKEN);
    // versioning
    FILE_PROPERTY_SET.add(PropertyIds.IS_LATEST_VERSION);
    FILE_PROPERTY_SET.add(PropertyIds.IS_MAJOR_VERSION);
    FILE_PROPERTY_SET.add(PropertyIds.IS_LATEST_MAJOR_VERSION);
    FILE_PROPERTY_SET.add(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT);
    FILE_PROPERTY_SET.add(PropertyIds.VERSION_SERIES_ID);
    FILE_PROPERTY_SET.add(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID);
    FILE_PROPERTY_SET.add(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY);
    FILE_PROPERTY_SET.add(PropertyIds.VERSION_LABEL);
    FILE_PROPERTY_SET.add(PropertyIds.CHECKIN_COMMENT);
  }

  /**
   * Set of properties for CMIS folder.
   */
  protected static final Set<String>       FOLDER_PROPERTY_SET  = new LinkedHashSet<String>();
  static {
    FOLDER_PROPERTY_SET.add(PropertyIds.OBJECT_ID);
    FOLDER_PROPERTY_SET.add(PropertyIds.PARENT_ID);
    FOLDER_PROPERTY_SET.add(PropertyIds.BASE_TYPE_ID);
    FOLDER_PROPERTY_SET.add(PropertyIds.OBJECT_TYPE_ID);
    FOLDER_PROPERTY_SET.add(PropertyIds.NAME);
    FOLDER_PROPERTY_SET.add(PropertyIds.IS_IMMUTABLE);
    FOLDER_PROPERTY_SET.add(PropertyIds.CREATED_BY);
    FOLDER_PROPERTY_SET.add(PropertyIds.CREATION_DATE);
    FOLDER_PROPERTY_SET.add(PropertyIds.LAST_MODIFIED_BY);
    FOLDER_PROPERTY_SET.add(PropertyIds.LAST_MODIFICATION_DATE);
    FOLDER_PROPERTY_SET.add(PropertyIds.CHANGE_TOKEN);
    // FOLDER_PROPERTY_SET.add(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT);
    // FOLDER_PROPERTY_SET.add(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID);
  }

  /**
   * Copied set of properties from OpenCMIS Workbench app with added extras.
   */
  @Deprecated
  private static final Set<String>         VERSION_PROPERTY_SET = new LinkedHashSet<String>();
  static {
    VERSION_PROPERTY_SET.add(PropertyIds.OBJECT_ID);
    VERSION_PROPERTY_SET.add(PropertyIds.OBJECT_TYPE_ID);
    VERSION_PROPERTY_SET.add(PropertyIds.NAME);
    VERSION_PROPERTY_SET.add(PropertyIds.VERSION_LABEL);
    VERSION_PROPERTY_SET.add(PropertyIds.IS_LATEST_VERSION);
    VERSION_PROPERTY_SET.add(PropertyIds.IS_MAJOR_VERSION);
    VERSION_PROPERTY_SET.add(PropertyIds.IS_LATEST_MAJOR_VERSION);
    VERSION_PROPERTY_SET.add(PropertyIds.CONTENT_STREAM_MIME_TYPE);
    VERSION_PROPERTY_SET.add(PropertyIds.CONTENT_STREAM_LENGTH);
    VERSION_PROPERTY_SET.add(PropertyIds.CONTENT_STREAM_FILE_NAME);
    VERSION_PROPERTY_SET.add(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT);
    // added extras
    VERSION_PROPERTY_SET.add(PropertyIds.CHANGE_TOKEN);
    VERSION_PROPERTY_SET.add(PropertyIds.CREATED_BY);
    VERSION_PROPERTY_SET.add(PropertyIds.CREATION_DATE);
    VERSION_PROPERTY_SET.add(PropertyIds.LAST_MODIFIED_BY);
    VERSION_PROPERTY_SET.add(PropertyIds.LAST_MODIFICATION_DATE);
    VERSION_PROPERTY_SET.add(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID);
    VERSION_PROPERTY_SET.add(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY);
    VERSION_PROPERTY_SET.add(PropertyIds.CHECKIN_COMMENT);
  }

  /**
   * Client session lock.
   */
  private final Lock                       lock                 = new ReentrantLock();

  /**
   * Session holder.
   */
  protected final AtomicReference<Session> session              = new AtomicReference<Session>();

  /**
   * Singleton of empty change token.
   */
  protected final ChangeToken              emptyToken           = new ChangeToken(EMPTY_TOKEN);

  /**
   * Client session parameters.
   */
  protected Map<String, String>            parameters;

  /**
   * Current CMIS repository Id.
   */
  protected String                         repositoryId;

  /**
   * Current CMIS repository name;
   */
  protected String                         repositoryName;

  protected String                         productName;

  protected String                         productVersion;

  protected String                         vendorName;

  protected String                         enterpriseId, enterpriseName, customDomain;

  /**
   * OpenCMIS context for object operations.
   */
  protected OperationContext               fileContext;

  /**
   * OpenCMIS context for folder operations.
   */
  protected OperationContext               folderContext;

  /**
   * Create API from user credentials.
   * 
   * @param serviceURL {@link String} CMIS service URL (AtimPub binding)
   * @param user {@link String} CMIS service username
   * @param password {@link String} CMIS service user password
   * @throws CMISException
   * @throws CloudDriveException
   */
  protected CMISAPI(String serviceURL, String user, String password) throws CMISException,
      CloudDriveException {

    // Prepare CMIS server parameters
    SessionParameterMap parameters = new SessionParameterMap();
    // TODO cleanup Map<String, String> parameters = new HashMap<String, String>();

    // User credentials.
    // parameters.put(SessionParameter.USER, user);
    // parameters.put(SessionParameter.PASSWORD, password);
    parameters.setUsernameTokenAuthentication(user, password, true);

    // Connection settings.
    // parameters.put(SessionParameter.ATOMPUB_URL, serviceURL);
    // parameters.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
    parameters.setAtomPubBindingUrl(serviceURL);

    // if need session locale?
    // parameters.put(SessionParameter.LOCALE_ISO3166_COUNTRY, "");
    // parameters.put(SessionParameter.LOCALE_ISO639_LANGUAGE, "de");

    // set as working settings in CMIS Workbench app
    parameters.setCompression(true);
    parameters.setClientCompression(false);
    parameters.setCookies(true);

    this.parameters = parameters;
  }

  /**
   * Update user credentials.
   * 
   * @param parameters {@link Map} of connection parameters
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
    RepositoryInfo info = getRepositoryInfo();
    this.repositoryName = info.getName();
    this.productName = info.getProductName();
    this.productVersion = info.getProductVersion();
    this.vendorName = info.getVendorName();
  }

  /**
   * Current CMIS repository.
   * 
   * @return the repository
   */
  public String getRepositoryId() {
    return repositoryId;
  }

  public String getRepositoryName() {
    return repositoryName != null ? repositoryName : repositoryId;
  }

  /**
   * @return the productName
   */
  public String getProductName() {
    return productName;
  }

  /**
   * @return the productVersion
   */
  public String getProductVersion() {
    return productVersion;
  }

  /**
   * @return the vendorName
   */
  public String getVendorName() {
    return vendorName;
  }

  public String getUserTitle() {
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
   * @throws UnauthorizedException
   */
  protected Folder getRootFolder() throws CMISException, RefreshAccessException, UnauthorizedException {
    try {
      return session().getRootFolder();
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error getting root folder: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data: use dedicated exception type to let upper code to recognize it
      throw new CMISInvalidArgumentException("Error getting root folder: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for getting root folder: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      throw new UnauthorizedException("Unauthorized for getting root folder", e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error getting root folder: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
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
   * @throws UnauthorizedException
   */
  protected CmisObject getObject(String objectId) throws CMISException,
                                                 NotFoundException,
                                                 CloudDriveAccessException,
                                                 UnauthorizedException {
    Session session = session();
    try {
      CmisObject object = readObject(objectId, session, fileContext);
      return object;
    } catch (CmisObjectNotFoundException e) {
      // XXX try find latest version by this id
      Document doc = readDocumentVersionOrPWC(objectId);
      if (doc != null) {
        return doc;
      } // else report original error
      throw new NotFoundException("Object not found: " + e.getMessage(), e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error reading object: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // XXX try find latest version by this id
      Document doc = readDocumentVersionOrPWC(objectId);
      if (doc != null) {
        return doc;
      } // else report original error
      // wrong input data: use dedicated exception type to let upper code to recognize it
      throw new CMISInvalidArgumentException("Error reading object: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for document content reading: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for object reading: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // user not authorized to read the object, was CloudDriveAccessException
      throw new UnauthorizedException("Unauthorized for reading object", e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error reading object: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error reading object: " + e.getMessage(), e);
    }
  }

  protected Document readDocumentVersionOrPWC(String id) throws CloudDriveAccessException,
                                                        UnauthorizedException {
    try {
      return getLatestDocumentVersionOrPWC(id);
    } catch (CMISException e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Error reading document version " + id, e);
      }
    } catch (NotFoundException e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Error reading document version" + id, e);
      }
    }
    // if service error or document not found we return null
    return null;
  }

  /**
   * Return latest versions of CMIS document in repository. If document is checked out by current user and the
   * private working copy (PWC) available it will be returned.
   * 
   * @param id {@link String}
   * @return {@link Document}
   * @throws CMISException
   * @throws NotFoundException
   * @throws CloudDriveAccessException
   * @throws UnauthorizedException
   */
  protected Document getLatestDocumentVersionOrPWC(String id) throws CMISException,
                                                             NotFoundException,
                                                             CloudDriveAccessException,
                                                             UnauthorizedException {
    Session session = session();
    try {
      Document document = session.getLatestDocumentVersion(id, fileContext);
      if (document.isVersionSeriesCheckedOut()) {
        // we have PWC, if it was checked out by current user we'll try return PWC object
        if (getUserTitle().equals(document.getVersionSeriesCheckedOutBy())) {
          String pwcid = document.getVersionSeriesCheckedOutId();
          if (pwcid != null) {
            try {
              return (Document) readObject(pwcid, session, fileContext);
            } catch (CmisBaseException e) {
              if (LOG.isDebugEnabled()) {
                LOG.debug("Cannot read object of version " + pwcid + " " + document.getName() + ": "
                    + e.getMessage() + ". Will use version document " + document.getId());
              }
            }
          }
        }
      }
      return document;
    } catch (CmisObjectNotFoundException e) {
      throw new NotFoundException("Document not found: " + e.getMessage(), e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error reading document: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data: use dedicated exception type to let upper code to recognize it
      throw new CMISInvalidArgumentException("Error reading document: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for document content reading: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document reading: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // user not authorized to read the document, was CloudDriveAccessException
      throw new UnauthorizedException("Unauthorized for reading document", e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error reading document: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error reading document: " + e.getMessage(), e);
    }
  }

  /**
   * Return all versions of CMIS document in repository.
   * 
   * @param document {@link Document}
   * @return list of {@link Document} versions
   * @throws CMISException
   * @throws NotFoundException
   * @throws CloudDriveAccessException
   * @throws UnauthorizedException
   */
  protected List<Document> getAllVersion(Document document) throws CMISException,
                                                           NotFoundException,
                                                           CloudDriveAccessException,
                                                           UnauthorizedException {
    try {
      return document.getAllVersions(fileContext);
    } catch (CmisObjectNotFoundException e) {
      throw new NotFoundException("Document not found: " + e.getMessage(), e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error reading document versions: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data: use dedicated exception type to let upper code to recognize it
      throw new CMISInvalidArgumentException("Error reading document versions: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for document versions content reading: "
          + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document versions reading: " + e.getMessage(),
                                       e);
    } catch (CmisUnauthorizedException e) {
      throw new UnauthorizedException("Unauthorized for reading document versions", e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error reading document versions: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error reading document versions: " + e.getMessage(), e);
    }
  }

  /**
   * Return the parent folder(s) for the specified object. If it is not fileable object then empty result will
   * be returned. A single parent will be always for a folder.
   * 
   * @param obj {@link CmisObject}
   * @return collection of {@link Folder} parents
   * @throws CMISException
   * @throws CloudDriveAccessException
   * @throws UnauthorizedException
   */
  protected Collection<Folder> getParents(CmisObject obj) throws CMISException,
                                                         CloudDriveAccessException,
                                                         UnauthorizedException {
    try {
      if (isFileable(obj)) {
        return ((FileableCmisObject) obj).getParents(folderContext);
      } else {
        return Collections.emptyList();
      }
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error reading object parents: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data: use dedicated exception type to let upper code to recognize it
      throw new CMISInvalidArgumentException("Error reading object parents (not a folder or root folder): "
          + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for object parents reading: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      throw new UnauthorizedException("Unauthorized for reading object parents", e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error reading object parents: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error reading object parents: " + e.getMessage(), e);
    }
  }

  protected ChildrenIterator getFolderItems(String folderId) throws CloudDriveException {
    return new ChildrenIterator(folderId);
  }

  protected ChangesIterator getChanges(ChangeToken changeToken) throws CMISException,
                                                               CloudDriveAccessException {
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
    LinkAccess link = (LinkAccess) session().getBinding().getObjectService();
    String linkContent = link.loadContentLink(repositoryId, file.getId());
    if (linkContent != null) {
      return linkContent;
    } else {
      return link.loadLink(repositoryId, file.getId(), Constants.REL_SELF, Constants.MEDIATYPE_ENTRY);
    }
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
    return linkSelfEntry;
  }

  protected Document createDocument(String parentId, String name, String mimeType, InputStream data) throws CMISException,
                                                                                                    NotFoundException,
                                                                                                    ConflictException,
                                                                                                    CloudDriveAccessException,
                                                                                                    ConstraintException,
                                                                                                    UnauthorizedException {
    Session session = session();
    try {
      CmisObject obj = null;

      // XXX workaround of CmisRuntimeException: null - read 3 times with pause
      int i = 0;
      CmisRuntimeException unexpected;
      do {
        i++;
        unexpected = null;
        try {
          obj = readObject(parentId, session, folderContext);
        } catch (CmisObjectNotFoundException e) {
          throw new NotFoundException("Parent not found: " + parentId, e);
        } catch (CmisRuntimeException e) {
          if (e.getMessage() == null && e.getErrorContent() == null) {
            unexpected = e;
            try {
              Thread.sleep(500);
            } catch (InterruptedException e1) {
              LOG.warn("Error sleeping in createDocument " + e.getMessage());
            }
          }
        }
      } while (obj == null && i <= 3);
      if (obj == null) {
        throw new CMISException("Cannot read parent object " + parentId + " of " + name, unexpected);
      }

      if (isFolder(obj)) {
        Folder parent = (Folder) obj;

        VersioningState vstate;
        ObjectType docType = session.getTypeDefinition(BaseTypeId.CMIS_DOCUMENT.value());
        if (isVersionable(docType)) {
          vstate = VersioningState.MAJOR;
        } else {
          vstate = VersioningState.NONE;
        }

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.OBJECT_TYPE_ID, BaseTypeId.CMIS_DOCUMENT.value());
        properties.put(PropertyIds.NAME, name);
        // created date not used as CMIS will set its own one
        // properties.put(PropertyIds.CREATION_DATE, created);

        // content length = -1 if it is unknown
        ContentStream contentStream = session.getObjectFactory()
                                             .createContentStream(name, -1, mimeType, data);
        return parent.createDocument(properties, contentStream, vstate, null, null, null, fileContext);
      } else {
        throw new CMISException("Parent not a folder: " + parentId + ", " + obj.getName());
      }
    } catch (CmisUpdateConflictException e) {
      // conflict actual for update/deletion/move
      throw new ConflictException("Document update conflict for '" + name + "'", e);
    } catch (CmisObjectNotFoundException e) {
      // this can be a rice condition when parent just deleted or similar happened remotely
      throw new NotFoundException("Error creating document: "
          + (e.getMessage() != null ? e.getMessage() : "object not found"), e);
    } catch (CmisNameConstraintViolationException e) {
      // name constraint considered as conflict (requires another name)
      // TODO check cyclic loop not possible due to infinite error - change name - error - change...
      throw new ConflictException("Unable to create document with name '" + name
          + "' due to repository constraints", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new ConstraintException("Unable to create document '" + name + "' due to repository constraints",
                                    e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error creating document: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISInvalidArgumentException("Error creating document: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for document content upload: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document creation: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      throw new UnauthorizedException("Unauthorized for creating document " + name, e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error creating document: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error creating document: " + e.getMessage(), e);
    }
  }

  protected Folder createFolder(String parentId, String name) throws CMISException,
                                                             NotFoundException,
                                                             ConflictException,
                                                             CloudDriveAccessException,
                                                             ConstraintException,
                                                             UnauthorizedException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = readObject(parentId, session, folderContext);
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
      throw new ConflictException("Unable to create folder with name '" + name
          + "' due to repository constraints", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new ConstraintException("Unable to create folder '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error creating folder: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISInvalidArgumentException("Error creating folder: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for folder creation: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      throw new UnauthorizedException("Unauthorized for creating folder " + name, e);
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
   * @throws ConstraintException
   * @throws UnauthorizedException
   */
  protected void deleteDocument(String id) throws CMISException,
                                          NotFoundException,
                                          ConflictException,
                                          CloudDriveAccessException,
                                          ConstraintException,
                                          UnauthorizedException {
    Session session = session();
    String name = "";
    try {
      CmisObject obj = readObject(id, session, fileContext);
      name = obj.getName();
      obj.delete(true);
    } catch (CmisObjectNotFoundException e) {
      throw new NotFoundException("Document not found: " + id, e);
    } catch (CmisUpdateConflictException e) {
      // conflict actual for update/deletion/move
      throw new ConflictException("Document removal conflict for '" + name + "'", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new ConstraintException("Unable to delete document '" + name + "' due to repository constraints",
                                    e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error deleting document: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISInvalidArgumentException("Error deleting document: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document removal: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      throw new UnauthorizedException("Unauthorized for deleting document", e);
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
   * @throws ConstraintException
   * @throws UnauthorizedException
   */
  protected void deleteFolder(String id) throws CMISException,
                                        NotFoundException,
                                        ConflictException,
                                        CloudDriveAccessException,
                                        ConstraintException,
                                        UnauthorizedException {
    Session session = session();
    String name = "";
    try {
      CmisObject obj = readObject(id, session, fileContext);
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
      throw new ConstraintException("Unable to delete folder '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error deleting folder: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISInvalidArgumentException("Error deleting folder: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for folder removal: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      throw new UnauthorizedException("Unauthorized for deleting folder", e);
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
   * @throws ConstraintException
   * @throws UnauthorizedException
   */
  protected Document updateContent(String id, String name, InputStream data, String mimeType, LocalFile local) throws CMISException,
                                                                                                              NotFoundException,
                                                                                                              ConflictException,
                                                                                                              CloudDriveAccessException,
                                                                                                              ConstraintException,
                                                                                                              UnauthorizedException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = readObject(id, session, fileContext);
      } catch (CmisObjectNotFoundException e) {
        // try use version series id to get an object
        String vid = local.findVersionSeriesId();
        if (vid != null) {
          obj = readDocumentVersionOrPWC(vid);
        } else {
          obj = null;
        }
        if (obj == null) {
          throw new NotFoundException("Object not found: " + id, e);
        }
      }
      if (isDocument(obj)) {
        Document document = (Document) obj;

        boolean checkin = false;
        Boolean isCO = document.isVersionSeriesCheckedOut();
        Boolean isPWC = document.isPrivateWorkingCopy();
        if ((isCO != null && isCO.booleanValue()) || (isPWC != null && isPWC.booleanValue())) {
          // already checked out
        } else {
          id = document.checkOut().getId(); // id of PWC here!
          obj = document = (Document) readObject(id, session, fileContext); // object of PWC state
          checkin = shouldCheckinRename(document);
        }

        // content length = -1 if it is unknown
        ContentStream contentStream = session.getObjectFactory()
                                             .createContentStream(name, -1, mimeType, data);

        if (checkin) {
          // do update in checkin call
          String message;
          if (!document.getName().equals(name)) {
            // simple rename
            document = (Document) rename(name, obj, session);
            message = "Content updated and renamed to " + name;
          } else {
            message = "Content updated";
          }

          // FYI rename via check-in properties may cause errors on some vendors:
          // MS SP: CmisConnectionException: Redirects are not supported (HTTP status code 302): Found
          id = document.checkIn(true, null, contentStream, message + " by " + getUserTitle()).getId();
          try {
            // read latest version document
            document = (Document) readObject(id, session, fileContext);
          } catch (CmisObjectNotFoundException e) {
            throw new NotFoundException("Checked-in object not found: " + id + " " + name, e);
          }
        } else {
          if (!document.getName().equals(name)) {
            document = (Document) rename(name, obj, session);
          }

          Document updatedDocument = document.setContentStream(contentStream, true);
          if (updatedDocument != null) {
            document = updatedDocument;
          } else {
            document = (Document) readObject(document.getId(), session, fileContext);
          }
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
      throw new ConstraintException("Unable to update document '" + name + "' due to repository constraints",
                                    e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error updating document: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISInvalidArgumentException("Error updating document: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for document content update: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document updating: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // user not authorized to update the document, was CloudDriveAccessException
      throw new UnauthorizedException("Unauthorized to update document " + name, e);
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
   * @throws ConstraintException
   * @throws UnauthorizedException
   */
  protected CmisObject updateObject(String parentId, String id, String name, LocalFile local) throws CMISException,
                                                                                             NotFoundException,
                                                                                             ConflictException,
                                                                                             CloudDriveAccessException,
                                                                                             ConstraintException,
                                                                                             UnauthorizedException {
    Session session = session();
    try {
      CmisObject result;
      CmisObject obj;
      try {
        obj = readObject(id, session, fileContext); // FIXME On SP id may be null here (not always, on file removal)
      } catch (CmisObjectNotFoundException e) {
        // try use version series id to get an object
        String vid = local.findVersionSeriesId();
        if (vid != null) {
          obj = readDocumentVersionOrPWC(vid);
        } else {
          obj = null;
        }
        if (obj == null) {
          throw new NotFoundException("Object not found: " + id, e);
        }
      }

      // update name
      if (!obj.getName().equals(name)) {
        // if versioned document is checked it - do check out it
        boolean checkin = false;
        Document document = null;
        if (isDocument(obj)) {
          document = (Document) obj;
          Boolean isCO = document.isVersionSeriesCheckedOut();
          Boolean isPWC = document.isPrivateWorkingCopy();
          if ((isCO != null && isCO.booleanValue()) || (isPWC != null && isPWC.booleanValue())) {
            // already checked out
          } else {
            id = document.checkOut().getId(); // id of PWC here!
            obj = document = (Document) readObject(id, session, fileContext); // object of PWC state
            checkin = shouldCheckinRename(document);
          }
        }

        // simple rename
        obj = result = rename(name, obj, session);

        if (checkin) {
          // FYI rename via check-in properties may cause errors on some vendors:
          // MS SP: CmisConnectionException: Redirects are not supported (HTTP status code 302): Found
          id = document.checkIn(true, null, null, "Renamed to " + name + " by " + getUserTitle()).getId();
          try {
            // read latest version document
            obj = result = readObject(id, session, fileContext);
          } catch (CmisObjectNotFoundException e) {
            throw new NotFoundException("Checked-in object not found: " + id + " " + name, e);
          }
        }
      }

      if (isFileable(obj)) {
        FileableCmisObject fileable = (FileableCmisObject) obj;

        // update parent if required
        // go through actual parents to find should we move/add the file to another parent
        List<Folder> parents = fileable.getParents(folderContext);
        boolean move = parents.size() > 0;
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
            obj = readObject(parentId, session, fileContext);
          } catch (CmisObjectNotFoundException e) {
            throw new NotFoundException("Parent not found: " + parentId, e);
          }
          if (isFolder(obj)) {
            Folder parent = (Folder) obj;
            Folder srcParent;
            if (parents.size() > 1) {
              // need lookup in local drive and compare with remote to find the srcParent
              String rpid = local.findRemoteParent(parentIds);
              if (rpid != null) {
                try {
                  obj = readObject(rpid, session, fileContext);
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

            FileableCmisObject moved = fileable.move(srcParent, parent, fileContext);
            if (moved != null) {
              result = moved;
            } else {
              fileable.refresh();
              result = fileable;
            }
          } else {
            throw new CMISException("Parent not a folder: " + parentId + ", " + obj.getName());
          }
        } else {
          result = obj;
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
      throw new ConstraintException("Unable to update object '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error updating object: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISInvalidArgumentException("Error updating object: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for object updating: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      // user not authorized to update the object, was CloudDriveAccessException
      throw new UnauthorizedException("Unauthorized to update object " + name, e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error updating object: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error updating object: " + e.getMessage(), e);
    }
  }

  /**
   * Rename CMIS object by better way working for the CMIS provider (this method can be overridden in
   * dedicated implementations). By default it uses obj.rename() method.
   * 
   * @param newName {@link String} new name
   * @param obj {@link CmisObject}
   * @param session {@link Session}
   * @return {@link CmisObject} renamed object
   */
  protected CmisObject rename(String newName, CmisObject obj, Session session) {
    ObjectId objId = obj.rename(newName, true);
    if (objId != null && objId instanceof CmisObject) {
      obj = (CmisObject) objId;
    } else {
      obj.refresh();
    }
    return obj;
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
   * @throws ConstraintException
   * @throws UnauthorizedException
   */
  protected Document copyDocument(String id, String parentId, String name) throws CMISException,
                                                                          NotFoundException,
                                                                          ConflictException,
                                                                          CloudDriveAccessException,
                                                                          ConstraintException,
                                                                          UnauthorizedException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = readObject(parentId, session, fileContext);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Parent not found: " + parentId, e);
      }
      if (isFolder(obj)) {
        Folder parent = (Folder) obj;
        try {
          obj = readObject(id, session, fileContext);
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
   * @throws ConstraintException
   * @throws UnauthorizedException
   */
  protected Document copyDocument(Document source, Folder parent, String name) throws CMISException,
                                                                              NotFoundException,
                                                                              ConflictException,
                                                                              CloudDriveAccessException,
                                                                              ConstraintException,
                                                                              UnauthorizedException {
    try {
      Session session = session();

      Map<String, Object> properties = new HashMap<String, Object>();
      properties.put(PropertyIds.BASE_TYPE_ID, source.getBaseType().getId());
      properties.put(PropertyIds.OBJECT_TYPE_ID, source.getType().getId());
      properties.put(PropertyIds.NAME, name);

      VersioningState vstate;
      ObjectType docType = session.getTypeDefinition(source.getBaseType().getId());
      if (isVersionable(docType)) {
        vstate = VersioningState.MAJOR;
      } else {
        vstate = VersioningState.NONE;
      }

      try {
        return parent.createDocumentFromSource(source, properties, vstate, null, null, null, fileContext);
      } catch (CmisNotSupportedException e) {
        LOG.warn("Cannot copy document " + source.getName() + " (" + source.getId() + ") to "
            + parent.getName() + "/" + name + ". Will try use actual content copying. " + e.getMessage());
        // createDocumentFromSource not supported, will do copying via client (it is AtomPub case)

        ContentStream sourceContent = source.getContentStream();
        ContentStream destContent = session.getObjectFactory()
                                           .createContentStream(name,
                                                                sourceContent.getLength(),
                                                                sourceContent.getMimeType(),
                                                                sourceContent.getStream());
        for (Property<?> p : source.getProperties()) {
          if (!properties.containsKey(p.getId())) {
            if (p.isMultiValued()) {
              properties.put(p.getId(), p.getValues());
            } else {
              properties.put(p.getId(), p.getValue());
            }
          }
        }
        return parent.createDocument(properties, destContent, vstate, null, null, null, fileContext);
      }
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
      throw new ConstraintException("Unable to copy document '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error copying document: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISInvalidArgumentException("Error copying document: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for document content copying: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for document copying: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      throw new UnauthorizedException("Unauthorized for copying document " + name, e);
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
   * @throws ConstraintException
   * @throws UnauthorizedException
   * 
   * @see #copyFolder(Folder, Folder, String)
   */
  protected Folder copyFolder(String id, String parentId, String name) throws CMISException,
                                                                      NotFoundException,
                                                                      ConflictException,
                                                                      CloudDriveAccessException,
                                                                      ConstraintException,
                                                                      UnauthorizedException {
    Session session = session();
    try {
      CmisObject obj;
      try {
        obj = readObject(parentId, session, fileContext);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Parent not found: " + parentId, e);
      }
      if (isFolder(obj)) {
        Folder parent = (Folder) obj;
        try {
          obj = readObject(id, session, fileContext);
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
      throw new CMISException("Error copying folder: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error copying folder: " + e.getMessage(), e);
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
   * @throws ConstraintException
   * @throws UnauthorizedException
   */
  protected Folder copyFolder(Folder source, Folder parent, String name) throws CMISException,
                                                                        NotFoundException,
                                                                        ConflictException,
                                                                        CloudDriveAccessException,
                                                                        ConstraintException,
                                                                        UnauthorizedException {
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
      throw new NotFoundException("Error copying folder: " + e.getMessage(), e);
    } catch (CmisNameConstraintViolationException e) {
      // name constraint considered as conflict (requires another name)
      // TODO check cyclic loop not possible due to infinite error - change name - error - change...
      throw new ConflictException("Unable to copy folder with name '" + name
          + "' due to repository constraints", e);
    } catch (CmisConstraintException e) {
      // repository/object level constraint considered as critical error (cancels operation)
      throw new ConstraintException("Unable to copy folder '" + name + "' due to repository constraints", e);
    } catch (CmisConnectionException e) {
      // communication (REST) error
      throw new CMISException("Error copying folder: " + e.getMessage(), e);
    } catch (CmisInvalidArgumentException e) {
      // wrong input data
      throw new CMISInvalidArgumentException("Error copying folder: " + e.getMessage(), e);
    } catch (CmisStreamNotSupportedException e) {
      throw new RefreshAccessException("Permission denied for folder content copying: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for folder copying: " + e.getMessage(), e);
    } catch (CmisUnauthorizedException e) {
      throw new UnauthorizedException("Unauthorized for copying folder " + name, e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error copying folder: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
      throw new CMISException("Error copying folder: " + e.getMessage(), e);
    }
  }

  protected RepositoryInfo getRepositoryInfo() throws CMISException, RefreshAccessException {
    try {
      return session(true).getRepositoryInfo();
    } catch (CmisRuntimeException e) {
      throw new CMISException("Error getting repository info: " + e.getMessage(), e);
    } catch (CmisObjectNotFoundException e) {
      throw new CMISException("Error getting repository info: " + e.getMessage(), e);
    } catch (CmisBaseException e) {
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
    } catch (CmisObjectNotFoundException e) {
      // Wrong service end-point used or incompatible CMIS version
      throw new WrongCMISProviderException("Error reading repositories list: " + e.getMessage(), e);
    } catch (CmisPermissionDeniedException e) {
      throw new RefreshAccessException("Permission denied for reading repositories list: " + e.getMessage(),
                                       e);
    } catch (CmisRuntimeException e) {
      throw new CMISException("Runtime error when reading CMIS repositories list", e);
    } catch (CmisBaseException e) {
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
    Session session = this.session.get();
    if (session != null && !forceNew) {
      // TODO should we check if session still alive (not closed)?
      return session;
    } else {
      for (Repository r : repositories()) {
        if (r.getId().equals(repositoryId)) {
          session = r.createSession();

          // default context
          OperationContext context = session.createOperationContext();
          context.setCacheEnabled(false);
          session.setDefaultContext(context);

          // file/document context
          ObjectType type = session.getTypeDefinition(BaseTypeId.CMIS_DOCUMENT.value());
          StringBuilder filter = new StringBuilder();
          for (String propId : FILE_PROPERTY_SET) {
            PropertyDefinition<?> propDef = type.getPropertyDefinitions().get(propId);
            if (propDef != null) {
              if (filter.length() > 0) {
                filter.append(',');
              }
              filter.append(propDef.getQueryName());
            }
          }
          type = session.getTypeDefinition(BaseTypeId.CMIS_FOLDER.value());
          for (String propId : FILE_PROPERTY_SET) {
            PropertyDefinition<?> propDef = type.getPropertyDefinitions().get(propId);
            if (propDef != null) {
              String qname = propDef.getQueryName();
              if (filter.indexOf(qname) < 0) {
                // add only if not already in the filter
                if (filter.length() > 0) {
                  filter.append(',');
                }
                filter.append(qname);
              }
            }
          }

          // check if repository supports and the user can request ACLs
          boolean includeAcls = !r.getCapabilities().getAclCapability().equals(CapabilityAcl.NONE);

          Context fileContext = new Context(filter.toString(), includeAcls, // includeAcls
                                            true, // includeAllowableActions
                                            true, // includePolicies
                                            IncludeRelationships.BOTH,
                                            "cmis:none", // renditions filter, Feb 15 was "*"
                                            null,
                                            OBJECT_PAGE_SIZE);

          // folder context
          filter = new StringBuilder();
          for (String propId : FOLDER_PROPERTY_SET) {
            PropertyDefinition<?> propDef = type.getPropertyDefinitions().get(propId);
            if (propDef != null) {
              if (filter.length() > 0) {
                filter.append(',');
              }
              filter.append(propDef.getQueryName());
            }
          }
          Context folderContext = new Context(filter.toString(), false, // includeAcls
                                              false, // includeAllowableActions
                                              false, // includePolicies
                                              IncludeRelationships.NONE,
                                              "cmis:none", // renditions filter
                                              null,
                                              FOLDER_PAGE_SIZE);

          this.session.set(session);
          // FYI contexts don't depend on session instance
          this.fileContext = fileContext;
          this.folderContext = folderContext;
          return session;
        }
      }
    }
    throw new CMISException("CMIS repository not found: " + repositoryId);
  }

  protected ChangeToken readToken(ChangeEvent event) throws CMISException {
    List<?> tl = event.getProperties().get("ChangeToken");
    if (tl != null && tl.size() > 0) {
      Object obj = tl.get(0);
      if (obj != null && obj instanceof String) {
        return readToken((String) obj);
      }
    }

    // try use event change time as a token
    GregorianCalendar time = event.getChangeTime();
    if (time != null) {
      return new TimeChangeToken(time);
    }
    throw new CMISException("ChangeToken property not found, change time is null for " + event.getObjectId()
        + " " + event.getChangeType());
  }

  protected ChangeToken readToken(String tokenString) throws CMISException {
    return new ChangeToken(tokenString);
  }

  protected ChangeToken emptyToken() {
    return emptyToken;
  }

  protected boolean isVersionable(ObjectType type) {
    return type instanceof DocumentType ? ((DocumentType) type).isVersionable() : false;
  }

  protected boolean isSyncableChange(ChangeEvent change) throws RefreshAccessException, CMISException {
    boolean res = true;
    List<?> objTypeIdList = change.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
    if (objTypeIdList != null) {
      res = false;
      for (Object tid : objTypeIdList) {
        if (tid instanceof String) {
          try {
            BaseTypeId btid = session().getTypeDefinition((String) tid, true).getBaseTypeId();
            if (btid.equals(BaseTypeId.CMIS_DOCUMENT)) {
              res = true;
            } else if (btid.equals(BaseTypeId.CMIS_FOLDER)) {
              res = true;
            }
          } catch (CmisRuntimeException e) {
            throw new CMISException("Error reading object type (" + tid + "): " + e.getMessage(), e);
          } catch (CmisObjectNotFoundException e) {
            throw new CMISException("Error reading object type (" + tid + "): " + e.getMessage(), e);
          } catch (CmisBaseException e) {
            throw new CMISException("Error reading object type (" + tid + "): " + e.getMessage(), e);
          }
        }
      }
    }
    return res;
  }

  /**
   * Should perform CMIS check-in after renaming of already checked-in document.
   * 
   * @return boolean
   */
  protected boolean shouldCheckinRename(Document doc) {
    // XXX this method created for MS SharePoint and OpenCMIS AtomPub bindings which don't support check-in.
    // Note that WSDL binding seems should work well for SharePoint.
    return true;
  }

  public static String formatTokenTime(Date date) {
    DateFormat format = new SimpleDateFormat(TOKEN_DATATIME_FORMAT);
    return format.format(date);
  }

}
