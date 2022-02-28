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
package org.exoplatform.clouddrive.cmis;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;

import org.apache.chemistry.opencmis.client.api.ChangeEvent;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.enums.CapabilityChanges;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.gatein.common.util.Base64;

import org.exoplatform.services.cms.clouddrives.CannotConnectDriveException;
import org.exoplatform.services.cms.clouddrives.CloudDriveAccessException;
import org.exoplatform.services.cms.clouddrives.CloudDriveException;
import org.exoplatform.services.cms.clouddrives.CloudFile;
import org.exoplatform.services.cms.clouddrives.CloudFileAPI;
import org.exoplatform.services.cms.clouddrives.CloudUser;
import org.exoplatform.services.cms.clouddrives.ConflictException;
import org.exoplatform.services.cms.clouddrives.DriveRemovedException;
import org.exoplatform.services.cms.clouddrives.NotFoundException;
import org.exoplatform.services.cms.clouddrives.SyncNotSupportedException;
import org.exoplatform.services.cms.clouddrives.UnauthorizedException;
import org.exoplatform.clouddrive.cmis.CMISAPI.ChangeToken;
import org.exoplatform.clouddrive.cmis.CMISAPI.ChangesIterator;
import org.exoplatform.clouddrive.cmis.CMISAPI.ChildrenIterator;
import org.exoplatform.clouddrive.cmis.CMISConnector.API;
import org.exoplatform.services.cms.clouddrives.jcr.JCRLocalCloudDrive;
import org.exoplatform.services.cms.clouddrives.jcr.JCRLocalCloudFile;
import org.exoplatform.services.cms.clouddrives.jcr.NodeFinder;
import org.exoplatform.ecm.connector.clouddrives.ContentService;
import org.exoplatform.services.cms.clouddrives.utils.ExtendedMimeTypeResolver;
import org.exoplatform.services.cms.clouddrives.viewer.ContentReader;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

/**
 * Local drive for CMIS provider.<br>
 */
public class JCRLocalCMISDrive extends JCRLocalCloudDrive {

  /**
   * Period to perform {@link Sync} as a next sync request. See implementation
   * of {@link #getSyncCommand()}.
   */
  public static final long      FULL_SYNC_PERIOD      = 24 * 60 * 60 * 1000;         // 24hrs

  /** The Constant PREVIOUS_LOCAL_NAME. */
  protected static final String PREVIOUS_LOCAL_NAME   = "cmiscd:previousLocalName";

  /** The Constant PREVIOUS_LOCAL_PARENT. */
  protected static final String PREVIOUS_LOCAL_PARENT = "cmiscd:previousLocalParent";

  /**
   * Connect algorithm for Template drive.
   */
  protected class Connect extends ConnectCommand {

    /** The api. */
    protected final CMISAPI api;

    /**
     * Instantiates a new connect.
     *
     * @throws RepositoryException the repository exception
     * @throws DriveRemovedException the drive removed exception
     */
    protected Connect() throws RepositoryException, DriveRemovedException {
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void fetchFiles() throws CloudDriveException, RepositoryException {
      ChangeToken changeToken = api.readToken(api.getRepositoryInfo().getLatestChangeLogToken());
      long changeId = System.currentTimeMillis(); // time of the begin

      // Folder root = fetchRoot(rootNode);
      Folder root = api.getRootFolder();
      // actual drive Id (its root folder's id) and URL, see initDrive() also
      driveNode.setProperty("ecd:id", root.getId());
      driveNode.setProperty("ecd:url", api.getLink(root));

      fetchChilds(root.getId(), driveNode);

      if (!Thread.currentThread().isInterrupted()) {
        initCMISItem(driveNode, root); // init parent

        // set change token from the start of the connect to let next sync fetch
        // all changes
        setChangeToken(driveNode, changeToken.getString());

        // sync position as current time of the connect start
        setChangeId(changeId);
      }
    }

    /**
     * Fetch childs.
     *
     * @param fileId the file id
     * @param parent the parent
     * @return the folder
     * @throws CloudDriveException the cloud drive exception
     * @throws RepositoryException the repository exception
     */
    protected Folder fetchChilds(String fileId, Node parent) throws CloudDriveException, RepositoryException {
      ChildrenIterator items = api.getFolderItems(fileId);
      iterators.add(items);
      while (items.hasNext() && !Thread.currentThread().isInterrupted()) {
        CmisObject item = items.next();
        if (api.isRelationship(item)) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Skipped relationship object: " + item.getId() + " " + item.getName());
          }
        } else {
          if (!isConnected(fileId, item.getId())) { // work if not already
                                                    // connected
            JCRLocalCloudFile localItem = updateItem(api, item, parent, null);
            if (localItem.isChanged()) {
              addConnected(fileId, localItem);
              if (localItem.isFolder()) {
                // go recursive to the folder
                fetchChilds(localItem.getId(), localItem.getNode());
              }
            } else {
              throw new CMISException("Fetched item was not added to local drive storage");
            }
          }
        }
      }
      return items.parent;
    }
  }

  /**
   * A facade {@link SyncCommand} implementation. This command will choose an
   * actual type of the synchronization depending on the CMIS repository
   * capabilities (Changes Log support).
   */
  protected class Sync extends SyncCommand {

    /**
     * An implementation of sync based on an CMIS change log.
     */
    protected class ChangesAlgorithm {

      /**
       * Changes from drive to apply.
       */
      protected ChangesIterator changes;

      /**
       * Gets the last change token.
       *
       * @return the last change token
       */
      protected ChangeToken getLastChangeToken() {
        ChangeToken lastToken = changes.getLastChangeToken();
        return lastToken != null ? lastToken : api.emptyToken();
      }

      /**
       * Sync files.
       *
       * @param fromChangeToken the from change token
       * @throws CloudDriveException the cloud drive exception
       * @throws RepositoryException the repository exception
       */
      protected void syncFiles(ChangeToken fromChangeToken) throws CloudDriveException, RepositoryException {
        changes = api.getChanges(fromChangeToken);
        iterators.add(changes);
        if (changes.hasNext()) {
          readLocalNodes(); // read all local nodes to nodes list
          CmisObject previousItem = null;
          ChangeEvent previousEvent = null;
          Set<String> previousParentIds = null;
          boolean skipFirst = true;
          while (changes.hasNext() && !Thread.currentThread().isInterrupted()) {
            ChangeEvent change = changes.next();
            ChangeToken lastToken = changes.getLastChangeToken();
            if (skipFirst && fromChangeToken.equals(lastToken)) {
              // skip first, it should be already applied by previous sync or
              // connect
              skipFirst = false;
              continue;
            }

            CmisObject item = null;
            Set<String> parentIds = new LinkedHashSet<String>();

            ChangeType changeType = change.getChangeType();
            String id = change.getObjectId();

            // use change.getProperties() to try get the object type and process
            // document/folder only
            if (api.isSyncableChange(change)) {
              if (!ChangeType.DELETED.equals(changeType)) {
                try {
                  item = api.getObject(change.getObjectId());
                } catch (NotFoundException e) {
                  // object not found on the server side, it could be removed
                  // during the fetch or CMIS
                  // implementation applies trashing (move to vendor specific
                  // Trash on deletion) -
                  // delete file locally
                  if (LOG.isDebugEnabled()) {
                    LOG.debug("File " + changeType.value() + " " + id + " not found remotely - apply DELETED logic.", e);
                  }
                }
              }

              if (item == null) {
                // file deleted
                if (hasRemoved(id)) {
                  cleanRemoved(id);
                  if (LOG.isDebugEnabled()) {
                    LOG.debug(">> Returned file removal (" + changeType.value() + ") " + id);
                  }
                } else {
                  if (LOG.isDebugEnabled()) {
                    LOG.debug(">> File removal (" + changeType.value() + ") " + id);
                  }
                }
                // even in case of local removal we check for all parents to
                // merge possible removals done in
                // parallel locally and remotely
                // FYI empty remote parents means item fully removed in the CMIS
                // repo
                // TODO check if DELETED happens in case of multi-filed file
                // unfiling
                deleteFile(id, new HashSet<String>());
              } else {
                // file created/updated
                String remoteName = item.getName();
                boolean isFolder = api.isFolder(item);

                // get parents
                if (isFolder) {
                  Folder p = ((Folder) item).getFolderParent();
                  if (p != null) {
                    parentIds.add(p.getId());
                  } else {
                    // else it's root folder
                    if (LOG.isDebugEnabled()) {
                      LOG.debug("Found change of folder without parent. Skipping it: " + id + " " + remoteName);
                    }
                    continue;
                  }
                } else if (api.isRelationship(item)) {
                  if (LOG.isDebugEnabled()) {
                    LOG.debug("Found change of relationship. Skipping it: " + id + " " + remoteName);
                  }
                  continue;
                } else {
                  // else we have fileable item
                  List<Folder> ps = ((FileableCmisObject) item).getParents(api.folderContext);
                  if (ps.size() == 0) {
                    // item has no parent, it can be undefined item or root
                    // folder - we skip it
                    if (LOG.isDebugEnabled()) {
                      LOG.debug("Found change of fileable item without parent. Skipping it: " + id + " " + remoteName);
                    }
                    continue;
                  } else {
                    for (Folder p : ps) {
                      parentIds.add(p.getId());
                    }
                  }
                }

                if (hasUpdated(id)) {
                  cleanUpdated(id);
                  if (LOG.isDebugEnabled()) {
                    LOG.debug(">> Returned file update (" + changeType.value() + ") " + id + " " + remoteName);
                  }
                } else {
                  if (LOG.isDebugEnabled()) {
                    LOG.debug(">> File update (" + changeType.value() + ") " + id + " " + remoteName);
                  }
                }

                // XXX nasty workround for Nuxeo versioning on file/folder
                // creation (it sends all versions
                // in events even for cmis:folder) - move to dedicated extension
                if (api.getVendorName().indexOf("Nuxeo") >= 0 && previousItem != null && remoteName.equals(previousItem.getName())
                    && previousEvent != null && ChangeType.CREATED.equals(previousEvent.getChangeType())
                    && ChangeType.CREATED.equals(changeType) && previousParentIds != null
                    && parentIds.containsAll(previousParentIds)) {
                  // same name object on the same parents was created by
                  // previous event - we assume this
                  // current as a 'version' of that previous and skip for the
                  // moment
                  if (LOG.isDebugEnabled()) {
                    LOG.debug(">> Skipping file update (" + changeType.value() + ") " + id + " " + remoteName);
                  }
                  // we keep previous* fields w/o change
                  continue;
                }

                updateFile(item, parentIds, isFolder);
              }
            } // else, skip the change of unsupported object type
            previousItem = item;
            previousEvent = change;
            previousParentIds = parentIds;
          }
        }
      }

      /**
       * Remove file's node.
       *
       * @param fileId {@link String}
       * @param parentIds set of Ids of parents (folders)
       * @throws RepositoryException the repository exception
       * @throws CloudDriveException the cloud drive exception
       */
      protected void deleteFile(String fileId, Set<String> parentIds) throws RepositoryException, CloudDriveException {
        List<Node> existing = nodes.get(fileId);
        if (existing != null) {
          // remove existing file,
          // also clean the nodes map from the descendants (they can be recorded
          // in following changes)
          for (Iterator<Node> enliter = existing.iterator(); enliter.hasNext();) {
            Node en = enliter.next();
            Node ep = en.getParent();
            if (fileAPI.isFolder(ep) || fileAPI.isDrive(ep)) {
              String parentId = fileAPI.getId(ep);
              // respect CMIS multi-filing and remove only if no parent exists
              // remotely
              if (!parentIds.contains(parentId)) {
                // this parent doesn't have the file in CMIS repo - remove it
                // with subtree locally
                removeLocalNode(en);
                // TODO cleanup after testing CMIS - May 12, 2018
                // for (Iterator<List<Node>> ecnliter =
                // nodes.values().iterator(); ecnliter.hasNext();) {
                // List<Node> ecnl = ecnliter.next();
                // if (ecnl != existing) {
                // for (Iterator<Node> ecniter = ecnl.iterator();
                // ecniter.hasNext();) {
                // Node ecn = ecniter.next();
                // if (ecn.getPath().startsWith(enpath)) {
                // ecniter.remove();
                // }
                // }
                // if (ecnl.size() == 0) {
                // ecnliter.remove();
                // }
                // } // else will be removed below
                // }
                // // explicitly remove file links outside the drive and remove
                // node
                // removeNode(en);
                // addRemoved(enpath); // add path to removed

                // This should be done as part of removeLocalNode()
                // enliter.remove(); // remove from existing list
              } // else this file filed on this parent in CMIS repo - keep it
                // locally also
            } else {
              LOG.warn("Skipped node with not cloud folder/drive parent: " + en.getPath());
            }
          }
          if (existing.size() == 0) {
            // TODO use real local file ID (can differ with remote side for
            // versioned documents)
            existing.remove(fileId);
          }
        }
      }

      /**
       * Create or update file's node.
       *
       * @param file {@link CmisObject}
       * @param parentIds set of Ids of parents (folders)
       * @param isFolder the is folder
       * @throws CloudDriveException the cloud drive exception
       * @throws RepositoryException the repository exception
       */
      protected void updateFile(CmisObject file, Set<String> parentIds, boolean isFolder) throws CloudDriveException,
                                                                                          RepositoryException {
        String id = file.getId();
        String name = file.getName();
        List<Node> existing = findDocumentNode(id, file, nodes);
        // Existing files being synchronized with cloud.
        Set<Node> synced = new HashSet<Node>();
        for (String pid : parentIds) {
          List<Node> fileParents = nodes.get(pid);
          if (fileParents == null) {
            throw new CMISException("Inconsistent changes: cannot find parent Node for " + id + " '" + name + "'");
          }

          for (Node fp : fileParents) {
            Node localNode = null;
            Node localNodeOther = null;
            if (existing == null) {
              existing = new ArrayList<Node>();
              nodes.put(id, existing);
            } else {
              for (Node n : existing) {
                if (n.getParent().isSame(fp)) {
                  localNode = n;
                } else if (localNodeOther == null) {
                  // this way first not found on the parent will be an other
                  localNodeOther = n;
                }
              }
            }

            if (localNode == null) {
              if (isFolder && localNodeOther != null) {
                // copy from local copy of the folder to a new parent
                localNode = copyFile(localNodeOther, fp);
              } // otherwise will be created below by updateItem() method
              // create/update node and update CMIS properties
              JCRLocalCloudFile localFile = updateItem(api, file, fp, localNode);
              addChanged(localFile);
              localNode = localFile.getNode();
              // add created/copied Node to list of existing
              existing.add(localNode);
            } else if (!fileAPI.getTitle(localNode).equals(name) && !wasRenamed(localNode, name)) {
              // file was renamed remotely - move its Node also
              JCRLocalCloudFile localFile = updateItem(api, file, fp, moveFile(id, name, localNode, fp));
              addChanged(localFile);
              localNode = localFile.getNode();
            } else {
              // update file metadata (this also includes file move - change of
              // the parent)
              JCRLocalCloudFile localFile = updateItem(api, file, fp, localNode);
              if (localFile.isChanged()) {
                addChanged(localFile);
              }
              localNode = localFile.getNode();
            }
            // cleanup of moved/renamed markers
            cleanRenamed(localNode, name);
            cleanMoved(localNode, pid);
            synced.add(localNode);
          }
        }

        if (existing != null) {
          // need remove other existing (not listed in changes parents)
          for (Iterator<Node> niter = existing.iterator(); niter.hasNext();) {
            Node n = niter.next();
            if (!synced.contains(n)) {
              removeLocalNode(n);
              // TODO cleanup after testing CMIS - May 12, 2018
              // niter.remove();
              // String path = n.getPath();
              // removeNode(n);
              // addRemoved(path);
            }
          }
        }
      }

    }

    /**
     * CMIS drive sync based on all remote files traversing: we do compare all
     * remote files with locals by its change log and fetch an item if the logs
     * differ.
     */
    protected class TraversingAlgorithm {

      /**
       * The Class CMISItem.
       */
      protected class CMISItem {

        /** The object. */
        protected final CmisObject object;

        /** The parent id. */
        protected final String     parentId;

        /** The postponed. */
        private boolean            postponed;

        /**
         * Instantiates a new CMIS item.
         *
         * @param object the object
         * @param parentId the parent id
         */
        protected CMISItem(CmisObject object, String parentId) {
          super();
          this.object = object;
          this.parentId = parentId;
        }

        /**
         * Postpone.
         */
        void postpone() {
          postponed = true;
        }

        /**
         * Checks if is postponed.
         *
         * @return true, if is postponed
         */
        boolean isPostponed() {
          return postponed;
        }
      }

      /**
       * The Class FolderReader.
       */
      protected class FolderReader implements Callable<Folder> {

        /** The folder id. */
        protected final String folderId;

        /**
         * Instantiates a new folder reader.
         *
         * @param folderId the folder id
         */
        protected FolderReader(String folderId) {
          super();
          this.folderId = folderId;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Folder call() throws Exception {
          // TODO will api return multi-filed file in each related folder?
          ChildrenIterator items = api.getFolderItems(folderId);
          iterators.add(items);
          while (items.hasNext() && !Thread.currentThread().isInterrupted()) {
            CmisObject obj = items.next();
            if (api.isRelationship(obj)) {
              if (LOG.isDebugEnabled()) {
                LOG.debug("Skipped relationship object: " + obj.getId() + " " + obj.getName());
              }
            } else {
              allItems.add(new CMISItem(obj, folderId));
              if (api.isFolder(obj)) {
                // go recursive to the folder in another thread
                readItems(obj.getId());
              }
            }
          }

          return items.parent;
        }
      }

      /** The all local. */
      protected final Map<String, List<Node>> allLocal = new HashMap<String, List<Node>>();

      /** The all items. */
      protected final Queue<CMISItem>         allItems = new ConcurrentLinkedQueue<CMISItem>();

      /** The readers. */
      protected final Queue<Future<Folder>>   readers  = new ConcurrentLinkedQueue<Future<Folder>>();

      /**
       * Read items.
       *
       * @param folderId the folder id
       * @return the future
       * @throws RepositoryException the repository exception
       * @throws CloudDriveException the cloud drive exception
       */
      protected Future<Folder> readItems(String folderId) throws RepositoryException, CloudDriveException {

        // start read items in another thread
        Future<Folder> reader = workerExecutor.submit(new FolderReader(folderId));
        readers.add(reader);
        return reader;
      }

      /**
       * Sync files.
       *
       * @throws RepositoryException the repository exception
       * @throws CloudDriveException the cloud drive exception
       * @throws InterruptedException the interrupted exception
       */
      protected void syncFiles() throws RepositoryException, CloudDriveException, InterruptedException {
        // real all local nodes of this drive
        readLocalNodes();

        // copy all drive map to use in // sync
        for (Map.Entry<String, List<Node>> ne : nodes.entrySet()) {
          allLocal.put(ne.getKey(), new ArrayList<Node>(ne.getValue())); // copy
                                                                         // lists
                                                                         // !
        }

        // sync with cloud
        Folder root = syncChilds(api.getRootFolder().getId());

        // remove local nodes of files not existing remotely, except of root
        nodes.remove(root.getId());
        boolean notInterrupted = true;
        for (Iterator<List<Node>> niter = nodes.values().iterator(); niter.hasNext()
            && (notInterrupted = !Thread.currentThread().isInterrupted());) {
          List<Node> nls = niter.next();
          next: for (Node n : nls) {
            String npath = n.getPath();
            for (String rpath : getRemoved()) {
              if (npath.startsWith(rpath)) {
                continue next;
              }
            }
            // remove file links outside the drive, then the node itself
            removeNode(n);
            addRemoved(npath);
          }
        }

        if (notInterrupted) {
          initCMISItem(driveNode, root); // init parent
          // we reset all saved local changes in the store as they aren't actual
          // for history
          rollbackAllChanges();
        }
        allLocal.clear();
        allItems.clear();
        readers.clear();
      }

      /**
       * Checks if is read done.
       *
       * @return true, if is read done
       */
      protected boolean isReadDone() {
        for (Iterator<Future<Folder>> riter = readers.iterator(); riter.hasNext();) {
          Future<Folder> r = riter.next();
          if (r.isDone()) {
            riter.remove();
          } else {
            return false;
          }
        }
        return true;
      }

      /**
       * Sync childs.
       *
       * @param folderId the folder id
       * @return the folder
       * @throws RepositoryException the repository exception
       * @throws CloudDriveException the cloud drive exception
       * @throws InterruptedException the interrupted exception
       */
      protected Folder syncChilds(final String folderId) throws RepositoryException, CloudDriveException, InterruptedException {

        // start read items in another thread
        Future<Folder> reader = readItems(folderId);

        // work with items already red in the queue
        CMISItem item;
        while (((item = allItems.poll()) != null || !isReadDone()) && !Thread.currentThread().isInterrupted()) {
          if (item != null) {
            CmisObject obj = item.object;
            List<Node> parentList = allLocal.get(item.parentId);
            if (parentList != null) {
              for (Node parent : parentList) {
                JCRLocalCloudFile localItem = updateItem(api, obj, parent, null);
                if (localItem.isChanged()) {
                  addChanged(localItem);
                  // maintain drive map with new/updated
                  List<Node> itemList = allLocal.get(localItem.getId());
                  if (itemList == null) {
                    itemList = new ArrayList<Node>();
                    allLocal.put(localItem.getId(), itemList);
                  }
                  itemList.add(localItem.getNode());
                }
                // remove this file (or folder subtree) from map of local to
                // mark it as existing,
                // others will be removed in syncFiles() after.
                String fileId = obj.getId();
                List<Node> existing = nodes.get(fileId);
                if (existing != null) {
                  String path = localItem.getPath();
                  for (Iterator<Node> eiter = existing.iterator(); eiter.hasNext();) {
                    Node enode = eiter.next();
                    if (enode.getPath().startsWith(path)) {
                      eiter.remove();
                    }
                  }
                  if (existing.size() == 0) {
                    nodes.remove(fileId);
                  }
                }
              }
            } else {
              // need wait for parent creation
              if (item.isPostponed()) {
                throw new CloudDriveException("Inconsistency error: parent cannot be found for remote file " + obj.getName());
              } else {
                allItems.add(item);
                item.postpone();
              }
            }
          } else {
            Thread.yield();
            Thread.sleep(50); // wait a bit
          }
        }

        // wait for the reader and return the parent (root) folder
        try {
          return reader.get();
        } catch (ExecutionException e) {
          LOG.error("Sync worker error: " + e.getMessage());
          Throwable c = e.getCause();
          if (c != null) {
            if (c instanceof CloudDriveException) {
              throw (CloudDriveException) c;
            } else if (c instanceof RepositoryException) {
              throw (RepositoryException) c;
            } else if (c instanceof RuntimeException) {
              throw (RuntimeException) c;
            } else if (c instanceof Error) {
              throw (Error) c;
            } else {
              throw new CMISException("Error in sync worker thread", c);
            }
          } else {
            throw new CMISException("Execution error in sync worker thread", e);
          }
        }
      }
    }

    /**
     * Internal API.
     */
    protected final CMISAPI       api;

    /** The pre sync error. */
    protected CloudDriveException preSyncError = null;

    /** The changes log. */
    protected ChangesAlgorithm    changesLog;

    /**
     * Instantiates a new sync.
     */
    protected Sync() {
      super();
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void preSyncFiles() throws CloudDriveException, RepositoryException, InterruptedException {
      // run preparation in try catch for CMIS errors, if error happen we
      // remember it for syncFiles() method,
      // where a full sync will be initiated to fix the erroneous drive state
      try {
        super.preSyncFiles();
      } catch (CMISException e) {
        this.preSyncError = e;
        // rollback all changes done by pre-sync
        rollback(driveNode);
        // We log the error and try fix the drive consistency by full sync
        // (below).
        LOG.warn("Synchronization error: failed to apply local changes to CMIS repository. " + "Full sync will be initiated for "
            + title(), e);
      } catch (NotFoundException e) {
        this.preSyncError = e;
        // rollback all changes done by pre-sync
        rollback(driveNode);
        // We log the error and try fix the drive consistency by full sync
        // (below).
        LOG.warn("Synchronization error: local changes inconsistent with CMIS repository. " + "Full sync will be initiated for "
            + title(), e);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void syncFiles() throws CloudDriveException, RepositoryException, InterruptedException {
      RepositoryInfo repoInfo = api.getRepositoryInfo();
      changesLog = null;

      ChangeToken changeToken = api.readToken(repoInfo.getLatestChangeLogToken());
      ChangeToken localChangeToken = api.readToken(getChangeToken(driveNode));
      ChangeToken lastChangeToken = api.emptyToken();

      // FYI ChangeId functionality not used by CMIS as we need composite token
      // here
      // but we maintain ChangeId for other uses
      long changeId = System.currentTimeMillis(); // time of the sync start

      if (!changeToken.isEmpty() && preSyncError == null) {
        if (!changeToken.equals(localChangeToken)) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("> Syncing changes from " + localChangeToken + " to " + changeToken);
          }
          try {
            if (CapabilityChanges.NONE != repoInfo.getCapabilities().getChangesCapability()) {
              // use algorithm based on CMIS Change Log: sync starting since
              // local token
              changesLog = new ChangesAlgorithm();
              changesLog.syncFiles(localChangeToken);
              lastChangeToken = changesLog.getLastChangeToken();
            } else {
              LOG.info("CMIS Change Log capability not supported by repository " + repoInfo.getName() + " ("
                  + repoInfo.getVendorName() + " " + repoInfo.getProductName() + " " + repoInfo.getProductVersion()
                  + "). Full synchronization will be used instead of the more efficient based on Change Log. "
                  + "Check if it is possible to enable Change Log for your repository.");
            }
          } catch (CMISException e) {
            // We log the error and try fix the drive consistency by full sync
            // (below).
            LOG.warn("Synchronization error: failed to read CMIS Change Log. Full sync will be initiated for " + title(), e);
            // rollback all changes done by ChangesAlgorithm
            rollback(driveNode);
          }
        } else {
          // else, no new changes
          return;
        }
      }

      if (!lastChangeToken.isEmpty() && preSyncError == null) {
        // changes sync well done, update sync position with its result and exit
        changeToken = lastChangeToken;
      } else {
        // by default, or if have previous errors, we use algorithm based on
        // full repository traversing
        LOG.info("Full synchronization initiated instead of the more efficient based on CMIS Change Log: " + title());
        new TraversingAlgorithm().syncFiles();
      }

      // set change token from the start of the connect to let next sync fetch
      // all further changes next time
      setChangeToken(driveNode, changeToken.getString());

      if (LOG.isDebugEnabled()) {
        LOG.debug("> Synced changes from " + localChangeToken + " to " + lastChangeToken);
      }

      // sync position as current time of the connect start
      setChangeId(changeId);
    }

    /**
     * {@inheritDoc}
     */
    protected void preSaveChunk() throws CloudDriveException, RepositoryException {
      if (changesLog != null) {
        // if chunk will be saved then also save the change token as last
        // applied in the drive
        // this will work for ChangesAlgorithm only
        ChangeToken lastChangeToken = changesLog.getLastChangeToken();
        if (!lastChangeToken.isEmpty()) {
          setChangeToken(driveNode, lastChangeToken.getString());
        }
      }
    }

    /**
     * Check if the file was renamed locally from given name. Such check actual
     * when we receive remote changes happened before a local rename of the
     * file.
     *
     * @param fileNode the file node
     * @param changeName the change name
     * @return true, if successful
     * @throws RepositoryException the repository exception
     */
    protected boolean wasRenamed(Node fileNode, String changeName) throws RepositoryException {
      if (fileNode.hasProperty(PREVIOUS_LOCAL_NAME)) {
        try {
          return fileNode.getProperty(PREVIOUS_LOCAL_NAME).getString().equals(changeName);
        } catch (RepositoryException e) {
          // ignore it here
          LOG.warn("Error reading " + PREVIOUS_LOCAL_NAME + " property of " + fileNode);
        }
      }
      return false;
    }

    /**
     * Remove a fact of local rename of the file from given name. This method
     * should be called when syncing from remote to local.
     *
     * @param fileNode the file node
     * @param fileName the file name
     * @throws RepositoryException the repository exception
     */
    protected void cleanRenamed(Node fileNode, String fileName) throws RepositoryException {
      if (fileNode.hasProperty(PREVIOUS_LOCAL_NAME)) {
        try {
          javax.jcr.Property pln = fileNode.getProperty(PREVIOUS_LOCAL_NAME);
          if (!pln.getString().equals(fileName)) {
            pln.remove();
          }
        } catch (RepositoryException e) {
          // ignore it here
          LOG.warn("Error cleaning " + PREVIOUS_LOCAL_NAME + " property of " + fileNode);
        }
      }
    }

    /**
     * Check if the file was moved locally from given parent. Such check actual
     * when we receive remote changes happened before a local move of the file.
     *
     * @param fileNode the file node
     * @param changeParentId the change parent id
     * @return true, if successful
     * @throws RepositoryException the repository exception
     */
    @Deprecated // TODO not used actually
    protected boolean wasMoved(Node fileNode, String changeParentId) throws RepositoryException {
      if (fileNode.hasProperty(PREVIOUS_LOCAL_PARENT)) {
        try {
          return fileNode.getProperty(PREVIOUS_LOCAL_PARENT).getString().equals(changeParentId);
        } catch (RepositoryException e) {
          // ignore it here
          LOG.warn("Error reading " + PREVIOUS_LOCAL_PARENT + " property of " + fileNode);
        }
      }
      return false;
    }

    /**
     * Remove a fact of local move of the file from given parent. This method
     * should be called when syncing from remote to local.
     *
     * @param fileNode the file node
     * @param parentId the parent id
     * @throws RepositoryException the repository exception
     */
    @Deprecated // TODO no sense to use it
    protected void cleanMoved(Node fileNode, String parentId) throws RepositoryException {
      if (fileNode.hasProperty(PREVIOUS_LOCAL_PARENT)) {
        try {
          javax.jcr.Property ppp = fileNode.getProperty(PREVIOUS_LOCAL_PARENT);
          if (!ppp.getString().equals(parentId)) {
            ppp.remove();
          }
        } catch (RepositoryException e) {
          // ignore it here
          LOG.warn("Error cleaning " + PREVIOUS_LOCAL_PARENT + " property of " + fileNode);
        }
      }
    }
  }

  /**
   * The Interface LocalFile.
   */
  public interface LocalFile {

    /**
     * Find an Id of remote parent not containing in locals of the file
     * referenced by this file Id.
     *
     * @param remoteParents {@link Set} of strings with Ids of remote parents
     * @return String an Id or <code>null</code> if remote parent not found
     * @throws CMISException the CMIS exception
     */
    String findRemoteParent(Set<String> remoteParents) throws CMISException;

    /**
     * Latest known locally version series id (cmiscd:versionSeriesId) of the
     * document or <code>null</code> if it such property not found (it is a
     * folder or versioning not available for the document).
     * 
     * @return String version series id or <code>null</code> if it cannot be
     *         found or error happened
     */
    String findVersionSeriesId();

    /**
     * Latest known locally id of checked-out document
     * (cmiscd:versionSeriesCheckedOutId) or or <code>null</code> if such
     * property not found (it is a folder or versioning not available for the
     * document).
     * 
     * @return String version series id or <code>null</code> if it cannot be
     *         found or error happened
     */
    String findVersionSeriesCheckedOutId();

    /**
     * Track file move locally.
     * 
     * @param prevParentId {@link String}
     */
    @Deprecated // TODO this makr not used actually
    void markMoved(String prevParentId);

    /**
     * Track file rename locally.
     * 
     * @param prevName {@link String}
     */
    void markRenamed(String prevName);
  }

  /**
   * {@link CloudFileAPI} implementation.
   */
  protected class FileAPI extends AbstractFileAPI {

    /**
     * The Class ContextLocalFile.
     */
    protected class ContextLocalFile implements LocalFile {

      /** The file id. */
      protected final String fileId;

      /** The file node. */
      protected final Node   fileNode;

      /**
       * Instantiates a new context local file.
       *
       * @param fileId the file id
       * @param fileNode the file node
       */
      protected ContextLocalFile(String fileId, Node fileNode) {
        this.fileId = fileId;
        this.fileNode = fileNode;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public String findRemoteParent(Set<String> remoteParents) throws CMISException {
        try {
          Collection<String> localParents = findParents(fileId);
          for (String rpid : remoteParents) {
            if (!localParents.contains(rpid)) {
              return rpid;
            }
          }
        } catch (DriveRemovedException e) {
          throw new CMISException(e);
        } catch (RepositoryException e) {
          throw new CMISException("Error finding file parents", e);
        }
        return null;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public String findVersionSeriesId() {
        try {
          return getVersionSeriesId(fileNode);
        } catch (PathNotFoundException e) {
          return null;
        } catch (RepositoryException e) {
          LOG.warn("Error reading local version series id: " + e.getMessage());
          return null;
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public String findVersionSeriesCheckedOutId() {
        try {
          return getVersionSeriesCheckedOutId(fileNode);
        } catch (PathNotFoundException e) {
          return null;
        } catch (RepositoryException e) {
          LOG.warn("Error reading local version series checked-out id: " + e.getMessage());
          return null;
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      @Deprecated // TODO mark not used actually
      public void markMoved(String prevParentId) {
        try {
          fileNode.setProperty(PREVIOUS_LOCAL_PARENT, prevParentId);
        } catch (RepositoryException e) {
          LOG.warn("Error storing " + PREVIOUS_LOCAL_PARENT + " : " + e.getMessage());
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void markRenamed(String prevName) {
        try {
          fileNode.setProperty(PREVIOUS_LOCAL_NAME, prevName);
        } catch (RepositoryException e) {
          LOG.warn("Error storing " + PREVIOUS_LOCAL_NAME + " : " + e.getMessage());
        }
      }
    }

    /**
     * Internal API.
     */
    protected final CMISAPI api;

    /**
     * Instantiates a new file API.
     */
    protected FileAPI() {
      this.api = getUser().api();
    }

    /**
     * Context.
     *
     * @param fileId the file id
     * @param fileNode the file node
     * @return the local file
     */
    protected LocalFile context(String fileId, Node fileNode) {
      return new ContextLocalFile(fileId, fileNode);
    }

    /**
     * Value of locally stored cmiscd:versionSeriesId or
     * {@link PathNotFoundException} if property cannot be found.
     * 
     * @param fileNode {@link Node}
     * @return String with value of cmiscd:versionSeriesId
     * @throws PathNotFoundException if cmiscd:versionSeriesId not found
     * @throws RepositoryException on storage error
     */
    protected String getVersionSeriesId(Node fileNode) throws PathNotFoundException, RepositoryException {
      return fileNode.getProperty("cmiscd:versionSeriesId").getString();
    }

    /**
     * Value of locally stored cmiscd:versionSeriesCheckedOutId or
     * {@link PathNotFoundException} if property cannot be found.
     *
     * @param node the node
     * @return String with value of cmiscd:versionSeriesCheckedOutId
     * @throws PathNotFoundException if cmiscd:versionSeriesCheckedOutId not
     *           found
     * @throws RepositoryException on storage error
     */
    protected String getVersionSeriesCheckedOutId(Node node) throws PathNotFoundException, RepositoryException {
      return node.getProperty("cmiscd:versionSeriesCheckedOutId").getString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile createFile(Node fileNode,
                                Calendar created,
                                Calendar modified,
                                String mimeType,
                                InputStream content) throws CloudDriveException, RepositoryException {

      String parentId = getParentId(fileNode);
      String title = getTitle(fileNode);
      Document file;
      try {
        file = api.createDocument(parentId, title, mimeType, content);
      } catch (ConflictException e) {
        // FYI we assume name as factor of equality here and make local file to
        // reflect the cloud side
        CmisObject existing = null;
        ChildrenIterator files = api.getFolderItems(parentId);
        while (files.hasNext()) {
          CmisObject item = files.next();
          if (title.equals(item.getName())) { // TODO do more complex?
            existing = item;
            break;
          }
        }
        if (existing == null || !api.isDocument(existing)) {
          throw e; // we cannot do anything at this level
        } else {
          file = (Document) existing;
          // FYI local file data will be erased by synchronizer after this call
        }
      }

      String id = file.getId();
      String name = file.getName();
      String link = api.getLink(file);
      String thumbnailLink = link;
      String createdBy = file.getCreatedBy();
      String modifiedBy = file.getLastModifiedBy();
      String type = findMimetype(file, mimeType);
      long size = file.getContentStreamLength();

      initFile(fileNode,
               id,
               name,
               type,
               link,
               null, // embedLink=null
               thumbnailLink, // downloadLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified,
               size);
      initCMISItem(fileNode, file);

      return new JCRLocalCloudFile(fileNode.getPath(),
                                   id,
                                   name,
                                   link,
                                   previewLink(null, fileNode),
                                   thumbnailLink,
                                   type,
                                   mimeTypes.getMimeTypeMode(type, name),
                                   modifiedBy,
                                   createdBy,
                                   created,
                                   modified,
                                   size,
                                   fileNode,
                                   true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile createFolder(Node folderNode, Calendar created) throws CloudDriveException, RepositoryException {

      String parentId = getParentId(folderNode);
      String title = getTitle(folderNode);
      Folder folder;
      try {
        folder = api.createFolder(getParentId(folderNode), getTitle(folderNode));
      } catch (ConflictException e) {
        // we assume name as factor of equality here
        CmisObject existing = null;
        ChildrenIterator files = api.getFolderItems(parentId);
        while (files.hasNext()) {
          CmisObject item = files.next();
          if (title.equals(item.getName())) { // TODO use more complex check if
                                              // required
            existing = item;
            break;
          }
        }
        if (existing == null || !api.isFolder(existing)) {
          throw e; // we cannot do anything at this level
        } else {
          folder = (Folder) existing;
        }
      }

      String id = folder.getId();
      String name = folder.getName();
      String link = api.getLink(folder);
      String createdBy = folder.getCreatedBy();
      String modifiedBy = folder.getLastModifiedBy();
      String type = folder.getType().getId();

      initFolder(folderNode,
                 id,
                 name,
                 type, //
                 link, // link
                 createdBy, // author
                 modifiedBy, // lastUser
                 created,
                 created); // created as modified here
      initCMISItem(folderNode, folder);

      return new JCRLocalCloudFile(folderNode.getPath(),
                                   id,
                                   name,
                                   link,
                                   type,
                                   modifiedBy,
                                   createdBy,
                                   created,
                                   created,
                                   folderNode,
                                   true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile updateFile(Node fileNode, Calendar modified) throws CloudDriveException, RepositoryException {

      String id = getId(fileNode);
      CmisObject obj = api.updateObject(getParentId(fileNode), id, getTitle(fileNode), context(id, fileNode));
      if (obj != null) {
        if (api.isDocument(obj)) {
          Document file = (Document) obj;
          id = file.getId();
          String name = file.getName();
          String link = api.getLink(file);
          String thumbnailLink = link;
          String createdBy = file.getCreatedBy();
          String modifiedBy = file.getLastModifiedBy();
          String type = file.getContentStreamMimeType();
          Calendar created = file.getCreationDate();
          modified = file.getLastModificationDate();
          long size = file.getContentStreamLength();

          initFile(fileNode,
                   id,
                   name,
                   type,
                   link,
                   null, // embedLink=null
                   thumbnailLink, // downloadLink
                   createdBy, // author
                   modifiedBy, // lastUser
                   created,
                   modified,
                   size);
          initCMISItem(fileNode, file);

          String filePath = fileNode.getPath();

          return new JCRLocalCloudFile(filePath,
                                       id,
                                       name,
                                       link,
                                       previewLink(null, fileNode),
                                       thumbnailLink,
                                       type,
                                       mimeTypes.getMimeTypeMode(type, name),
                                       modifiedBy,
                                       createdBy,
                                       created,
                                       modified,
                                       size,
                                       fileNode,
                                       true);
        } else {
          throw new CMISException("Object not a document: " + id + ", " + obj.getName());
        }
      } // else file wasn't changed actually
      return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile updateFolder(Node folderNode, Calendar modified) throws CloudDriveException, RepositoryException {

      String id = getId(folderNode);
      CmisObject obj = api.updateObject(getParentId(folderNode), id, getTitle(folderNode), context(id, folderNode));
      if (obj != null) {
        if (api.isFolder(obj)) {
          Folder folder = (Folder) obj;
          id = folder.getId();
          String name = folder.getName();
          String link = api.getLink(folder);
          String createdBy = folder.getCreatedBy();
          String modifiedBy = folder.getLastModifiedBy();
          String type = folder.getType().getId();
          Calendar created = folder.getCreationDate();
          modified = folder.getLastModificationDate();

          initFolder(folderNode,
                     id,
                     name,
                     type, //
                     link, // link
                     createdBy, // author
                     modifiedBy, // lastUser
                     created,
                     modified);
          initCMISItem(folderNode, folder);

          String folderPath = folderNode.getPath();

          return new JCRLocalCloudFile(folderPath,
                                       id,
                                       name,
                                       link,
                                       type,
                                       modifiedBy,
                                       createdBy,
                                       created,
                                       modified,
                                       folderNode,
                                       true);
        } else {
          throw new CMISException("Object not a folder: " + id + ", " + obj.getName());
        }
      } // else folder wasn't changed actually
      return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile updateFileContent(Node fileNode,
                                       Calendar modified,
                                       String mimeType,
                                       InputStream content) throws CloudDriveException, RepositoryException {
      // Update existing file content and its metadata.
      String fileId = getId(fileNode);
      Document file = api.updateContent(fileId, getTitle(fileNode), content, mimeType, context(fileId, fileNode));

      String id = file.getId();
      String name = file.getName();
      String link = api.getLink(file);
      String thumbnailLink = link;
      String createdBy = file.getCreatedBy();
      String modifiedBy = file.getLastModifiedBy();
      String type = file.getContentStreamMimeType();
      Calendar created = file.getCreationDate();
      modified = file.getLastModificationDate();
      long size = file.getContentStreamLength();

      initFile(fileNode,
               id,
               name,
               type,
               link,
               null, // embedLink=null
               thumbnailLink, // downloadLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified,
               size);
      initCMISItem(fileNode, file);

      return new JCRLocalCloudFile(fileNode.getPath(),
                                   id,
                                   name,
                                   link,
                                   previewLink(null, fileNode),
                                   thumbnailLink,
                                   type,
                                   mimeTypes.getMimeTypeMode(type, name),
                                   modifiedBy,
                                   createdBy,
                                   created,
                                   modified,
                                   size,
                                   fileNode,
                                   true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile copyFile(Node srcFileNode, Node destFileNode) throws CloudDriveException, RepositoryException {
      String parentId = getParentId(destFileNode);
      CmisObject obj;
      try {
        obj = api.getObject(parentId);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Parent not found: " + parentId, e);
      }
      if (api.isFolder(obj)) {
        Folder parent = (Folder) obj;
        String sourceId = getId(srcFileNode);
        try {
          obj = api.getObject(sourceId);
        } catch (CmisObjectNotFoundException e) {
          throw new NotFoundException("Source not found: " + sourceId, e);
        }
        if (api.isDocument(obj)) {
          Document source = (Document) obj;
          return copyFile(source, parent, destFileNode);
        } else {
          throw new CMISException("Source not a document: " + sourceId + ", " + obj.getName());
        }
      } else {
        throw new CMISException("Parent not a folder: " + parentId + ", " + obj.getName());
      }

      // TODO cleanup
      // Document file = api.copyDocument(getId(srcFileNode),
      // getParentId(destFileNode),
      // getTitle(destFileNode));
    }

    /**
     * Copy file.
     *
     * @param srcFile the src file
     * @param destParent the dest parent
     * @param destFileNode the dest file node
     * @return the cloud file
     * @throws CloudDriveException the cloud drive exception
     * @throws RepositoryException the repository exception
     */
    protected CloudFile copyFile(Document srcFile, Folder destParent, Node destFileNode) throws CloudDriveException,
                                                                                         RepositoryException {
      Document file = api.copyDocument(srcFile, destParent, getTitle(destFileNode));

      String id = file.getId();
      String name = file.getName();
      String link = api.getLink(file);
      String thumbnailLink = link;
      String createdBy = file.getCreatedBy();
      String modifiedBy = file.getLastModifiedBy();
      String type = file.getContentStreamMimeType();
      Calendar created = file.getCreationDate();
      Calendar modified = file.getLastModificationDate();
      long size = file.getContentStreamLength();

      initFile(destFileNode,
               id,
               name,
               type,
               link,
               null, // embedLink=null
               thumbnailLink, // thumbnailLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified,
               size);
      initCMISItem(destFileNode, file);

      return new JCRLocalCloudFile(destFileNode.getPath(),
                                   id,
                                   name,
                                   link,
                                   previewLink(null, destFileNode),
                                   thumbnailLink,
                                   type,
                                   mimeTypes.getMimeTypeMode(type, name),
                                   modifiedBy,
                                   createdBy,
                                   created,
                                   modified,
                                   size,
                                   destFileNode,
                                   true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile copyFolder(Node srcFolderNode, Node destFolderNode) throws CloudDriveException, RepositoryException {

      String parentId = getParentId(destFolderNode);
      CmisObject obj;
      try {
        obj = api.getObject(parentId);
      } catch (CmisObjectNotFoundException e) {
        throw new NotFoundException("Parent not found: " + parentId, e);
      }
      if (api.isFolder(obj)) {
        Folder parent = (Folder) obj;
        String sourceId = getId(srcFolderNode);
        try {
          obj = api.getObject(sourceId);
        } catch (CmisObjectNotFoundException e) {
          throw new NotFoundException("Source not found: " + sourceId, e);
        }
        if (api.isFolder(obj)) {
          Folder source = (Folder) obj;
          return copyFolder(source, parent, destFolderNode);
        } else {
          throw new CMISException("Source not a folder: " + sourceId + ", " + obj.getName());
        }
      } else {
        throw new CMISException("Parent not a folder: " + parentId + ", " + obj.getName());
      }

      // TODO cleanup
      // Folder folder = api.copyFolder(getId(srcFolderNode),
      // getParentId(destFolderNode),
      // getTitle(destFolderNode));
    }

    /**
     * Copy folder.
     *
     * @param srcFolder the src folder
     * @param destParent the dest parent
     * @param destFolderNode the dest folder node
     * @return the cloud file
     * @throws CloudDriveException the cloud drive exception
     * @throws RepositoryException the repository exception
     */
    protected CloudFile copyFolder(Folder srcFolder, Folder destParent, Node destFolderNode) throws CloudDriveException,
                                                                                             RepositoryException {

      String name = getTitle(destFolderNode);
      Folder folder = api.copyFolder(srcFolder, destParent, name);

      // traverse the source sub-tree and copy all its children to the
      // destination recursively
      ChildrenIterator childs = api.getFolderItems(srcFolder);
      while (childs.hasNext()) {
        CmisObject child = childs.next();
        String childName = child.getName();
        // read local node by its file name and file ID of the source's child -
        // in the copy* methods the ID
        // will be updated with an actual value of copied remotely file
        Node fileNode = readNode(destFolderNode, childName, child.getId());
        if (fileNode != null) {
          if (api.isDocument(child)) {
            copyFile((Document) child, folder, fileNode);
          } else if (child instanceof Folder) {
            copyFolder((Folder) child, folder, fileNode);
          }
        } else {
          LOG.warn("Ignoring not existing locally child node " + name + "/" + childName + " in " + destFolderNode.getPath());
          // XXX just copy remotely and skip updating local nodes assuming it
          // will be synced by a next sync
          if (api.isDocument(child)) {
            api.copyDocument((Document) child, folder, childName);
          } else if (child instanceof Folder) {
            api.copyFolder((Folder) child, folder, child.getName());
          }
        }
      }

      // init local node
      String id = folder.getId();
      name = folder.getName(); // use final name
      String link = api.getLink(folder);
      String createdBy = folder.getCreatedBy();
      String modifiedBy = folder.getLastModifiedBy();
      String type = folder.getType().getId();
      Calendar created = folder.getCreationDate();
      Calendar modified = folder.getLastModificationDate();

      initFolder(destFolderNode,
                 id,
                 name,
                 type, //
                 link, // link
                 createdBy, // author
                 modifiedBy, // lastUser
                 created,
                 modified);
      initCMISItem(destFolderNode, folder);

      return new JCRLocalCloudFile(destFolderNode.getPath(),
                                   id,
                                   name,
                                   link,
                                   type,
                                   modifiedBy,
                                   createdBy,
                                   created,
                                   modified,
                                   destFolderNode,
                                   true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeFile(String id) throws CloudDriveException, RepositoryException {
      api.deleteDocument(id);
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeFolder(String id) throws CloudDriveException, RepositoryException {
      api.deleteFolder(id);
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean trashFile(String id) throws CloudDriveException, RepositoryException {
      throw new SyncNotSupportedException("Trash not supported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean trashFolder(String id) throws CloudDriveException, RepositoryException {
      throw new SyncNotSupportedException("Trash not supported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile untrashFile(Node fileNode) throws CloudDriveException, RepositoryException {
      throw new SyncNotSupportedException("Trash not supported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile untrashFolder(Node folderNode) throws CloudDriveException, RepositoryException {
      throw new SyncNotSupportedException("Trash not supported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTrashSupported() {
      return false;
    }

    /**
     * Restore.
     *
     * @param obj the obj
     * @param parent the parent
     * @return the JCR local cloud file
     * @throws CloudDriveException the cloud drive exception
     * @throws RepositoryException the repository exception
     */
    private JCRLocalCloudFile restore(CmisObject obj, Node parent) throws CloudDriveException, RepositoryException {
      JCRLocalCloudFile localItem = updateItem(api, obj, parent, null);
      Node localNode = localItem.getNode();
      if (localNode.isNew() && localItem.isFolder()) {
        // folder just created - go recursive to fetch all childs from the
        // remote side
        ChildrenIterator childs = api.getFolderItems(localItem.getId());
        while (childs.hasNext()) {
          restore(childs.next(), localNode);
        }
      }
      return localItem;
    }

    /**
     * {@inheritDoc}
     */
    public JCRLocalCloudFile restore(String id, String path) throws CloudDriveException, RepositoryException {
      JCRLocalCloudFile result = null;
      try {
        CmisObject remote = api.getObject(id);
        List<Folder> remoteParents = new ArrayList<Folder>(api.getParents(remote));

        // go through all local nodes existing with given file id
        // and restore if its parent exists remotely, or remove local node
        // otherwise
        for (Node node : findNodes(Arrays.asList(id))) {
          Node localParent = node.getParent();
          String parentId = fileAPI.getId(localParent);

          JCRLocalCloudFile restored = null;
          for (Iterator<Folder> rpiter = remoteParents.iterator(); rpiter.hasNext();) {
            Folder remoteParent = rpiter.next();
            String rpid = remoteParent.getId();
            if (parentId.equals(rpid)) {
              // restore file or sub-tree: update local file
              restored = restore(remote, localParent);
              rpiter.remove(); // this parent restored - remove it from the
                               // scope
              if (path.equals(node.getPath())) {
                result = restored;
              }
              // break; we could force break here, but let's rely on remote
              // parents consistency
            }
          }

          if (restored == null) {
            // nothing restored - this local parent should not contain the file
            // only if it is not already ignored
            removeNode(node);
          }
        }

        // if this list not empty then we need restore not existing locally
        // file(s)
        for (Folder remoteParent : remoteParents) {
          String rpid = remoteParent.getId();
          // find all nodes of this remote parent, this way we respect
          // "multifiling" of folders, what is not
          // possible according CMIS spec, but who knows vendors :)
          for (Node parent : findNodes(Arrays.asList(rpid))) {
            // restore file or sub-tree: create local file
            JCRLocalCloudFile restored = restore(remote, parent);
            if (result == null) {
              result = restored;
            }
          }
        }
      } catch (NotFoundException e) {
        // Remove locally to reflect the remote state
        for (Node node : findNodes(Arrays.asList(id))) {
          removeNode(node);
        }
      }
      // result will be null if no node restored but may be removed obsolete
      return result;
    }
  }

  /**
   * The Class DocumentContent.
   */
  protected class DocumentContent implements ContentReader {

    /** The content. */
    protected final ContentStream content;

    /** The type. */
    protected final String        type;

    /** The length. */
    protected final long          length;

    /** The file name. */
    protected final String        fileName;

    /**
     * Instantiates a new document content.
     *
     * @param content the content
     * @param type the type
     * @param fileName the file name
     */
    protected DocumentContent(ContentStream content, String type, String fileName) {
      this.content = content;
      this.length = content.getLength();
      this.type = type != null ? type : content.getMimeType();
      this.fileName = fileName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InputStream getStream() {
      return content.getStream();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getMimeType() {
      return type;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTypeMode() {
      return mimeTypes.getMimeTypeMode(type, fileName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLength() {
      return length;
    }
  }

  /** The change id sequencer. */
  protected final AtomicLong changeIdSequencer = new AtomicLong(0);

  /**
   * Platform server host URL, used for preview URL generation.
   */
  protected final String     exoURL;

  /**
   * Instantiates a new JCR local CMIS drive.
   *
   * @param user the user
   * @param driveNode the drive node
   * @param sessionProviders the session providers
   * @param finder the finder
   * @param mimeTypes the mime types
   * @param exoURL the exo URL
   * @throws CloudDriveException the cloud drive exception
   * @throws RepositoryException the repository exception
   */
  protected JCRLocalCMISDrive(CMISUser user,
                              Node driveNode,
                              SessionProviderService sessionProviders,
                              NodeFinder finder,
                              ExtendedMimeTypeResolver mimeTypes,
                              String exoURL)
      throws CloudDriveException, RepositoryException {
    super(user, driveNode, sessionProviders, finder, mimeTypes);
    this.exoURL = exoURL;
    CMISAPI api = user.api();
    saveAccess(driveNode, api.getPassword(), api.getServiceURL(), api.getRepositoryId());
  }

  /**
   * Instantiates a new JCR local CMIS drive.
   *
   * @param apiBuilder the api builder
   * @param driveNode the drive node
   * @param sessionProviders the session providers
   * @param finder the finder
   * @param mimeTypes the mime types
   * @param exoURL the exo URL
   * @throws RepositoryException the repository exception
   * @throws CloudDriveException the cloud drive exception
   */
  protected JCRLocalCMISDrive(API apiBuilder,
                              Node driveNode,
                              SessionProviderService sessionProviders,
                              NodeFinder finder,
                              ExtendedMimeTypeResolver mimeTypes,
                              String exoURL)
      throws RepositoryException, CloudDriveException {
    super(loadUser(apiBuilder, driveNode), driveNode, sessionProviders, finder, mimeTypes);
    this.exoURL = exoURL;
  }

  /**
   * Load user from the drive Node.
   *
   * @param apiBuilder {@link API} API builder
   * @param driveNode {@link Node} root of the drive
   * @return {@link CMISUser}
   * @throws RepositoryException the repository exception
   * @throws CMISException the CMIS exception
   * @throws CloudDriveException the cloud drive exception
   */
  protected static CMISUser loadUser(API apiBuilder, Node driveNode) throws RepositoryException,
                                                                     CMISException,
                                                                     CloudDriveException {
    String userName = driveNode.getProperty("ecd:cloudUserName").getString();
    String email = driveNode.getProperty("ecd:userEmail").getString();
    String userId = driveNode.getProperty("ecd:cloudUserId").getString();

    String accessKey = driveNode.getProperty("cmiscd:accessKey").getString();
    try {
      String password = new String(Base64.decode(accessKey), "UTF-8");
      String serviceURL = driveNode.getProperty("cmiscd:serviceURL").getString();
      String repositoryId = driveNode.getProperty("cmiscd:repositoryId").getString();
      CMISAPI driveAPI = apiBuilder.auth(userName, password).serviceUrl(serviceURL).build();
      driveAPI.initRepository(repositoryId);
      return apiBuilder.createUser(userId, userName, email, driveAPI);
    } catch (UnsupportedEncodingException e) {
      throw new CloudDriveException("Error decoding user key", e);
    }
  }

  /**
   * Save user credentials in local drive. For use in new drive creation and for
   * {@link #updateAccess(CloudUser)}.
   *
   * @param driveNode {@link String}
   * @param password {@link String}
   * @param serviceURL {@link String} optional if it's access update
   * @param repositoryId {@link String} optional if it's access update
   * @throws CloudDriveException the cloud drive exception
   */
  protected void saveAccess(Node driveNode, String password, String serviceURL, String repositoryId) throws CloudDriveException {
    try {
      jcrListener.disable();
      try {
        // TODO more sophisticated password protection?
        String accessKey = Base64.encodeBytes(password.getBytes("UTF-8"));
        driveNode.setProperty("cmiscd:accessKey", accessKey);
        if (serviceURL != null) {
          driveNode.setProperty("cmiscd:serviceURL", serviceURL);
        } else {
          if (!driveNode.hasProperty("cmiscd:serviceURL")) {
            rollback(driveNode);
            throw new CloudDriveException("CMIS service URL required for user access");
          }
        }
        if (repositoryId != null) {
          driveNode.setProperty("cmiscd:repositoryId", repositoryId);
        } else {
          if (!driveNode.hasProperty("cmiscd:repositoryId")) {
            rollback(driveNode);
            throw new CloudDriveException("CMIS repository ID required for user access");
          }
        }
        driveNode.save();
      } catch (RepositoryException e) {
        rollback(driveNode);
        throw new CloudDriveException("Error saving user key", e);
      } catch (UnsupportedEncodingException e) {
        throw new CloudDriveException("Error encoding user key", e);
      }
    } finally {
      jcrListener.enable();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ConnectCommand getConnectCommand() throws DriveRemovedException, RepositoryException {
    return new Connect();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected SyncCommand getSyncCommand() throws DriveRemovedException, SyncNotSupportedException, RepositoryException {
    return new Sync();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudFileAPI createFileAPI() throws DriveRemovedException, SyncNotSupportedException, RepositoryException {
    return new FileAPI();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected Long readChangeId() throws RepositoryException, CloudDriveException {
    try {
      return rootNode().getProperty("cmiscd:changeTimestamp").getLong();
    } catch (PathNotFoundException e) {
      throw new CloudDriveException("Change id not found for the drive " + title());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void saveChangeId(Long id) throws CloudDriveException, RepositoryException {
    Node driveNode = rootNode();
    // will be saved in a single save of the drive command (sync)
    driveNode.setProperty("cmiscd:changeTimestamp", id);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CMISUser getUser() {
    return (CMISUser) user;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void refreshAccess() throws CloudDriveException {
    // not required for general CMIS
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void updateAccess(CloudUser newUser) throws CloudDriveException, RepositoryException {
    CMISAPI newAPI = ((CMISUser) newUser).api();
    String user = newAPI.getUser();
    String password = newAPI.getPassword();

    try {
      Node driveNode = rootNode();
      String userId = driveNode.getProperty("ecd:cloudUserId").getString();
      if (userId.equals(user)) {
        saveAccess(driveNode, password, null, null);
        getUser().api().updateUser(newAPI.getParamaters());
      } else {
        throw new CloudDriveException("User doesn't match to access key: " + user);
      }
    } catch (DriveRemovedException e) {
      throw new CloudDriveException("Error openning drive node: " + e.getMessage(), e);
    } catch (RepositoryException e) {
      throw new CloudDriveException("Error reading drive node: " + e.getMessage(), e);
    }
  }

  /**
   * Initialize CMIS specifics of files and folders.
   *
   * @param node {@link Node}
   * @param item {@link CmisObject}
   * @throws RepositoryException the repository exception
   * @throws CMISException the CMIS exception
   */
  protected void initCMISItem(Node node, CmisObject item) throws RepositoryException, CMISException {

    // changeToken used for synchronization
    setChangeToken(node, item.getChangeToken());

    // TODO probably useless - seems it is OpenCMIS internal time since last
    // update of caches with the server
    node.setProperty("cmiscd:refreshTimestamp", item.getRefreshTimestamp());

    // properties below not actually used by the Cloud Drive,
    // they are just for information available to PLF user
    node.setProperty("cmiscd:description", item.getDescription());

    // persist CMIS versioning properties
    Property<Boolean> pbv = item.getProperty("cmis:isLatestVersion");
    if (pbv != null) {
      node.setProperty("cmiscd:isLatestVersion", pbv.getFirstValue());
    } else {
      node.setProperty("cmiscd:isLatestVersion", (String) null);
    }
    pbv = item.getProperty("cmis:isMajorVersion");
    if (pbv != null) {
      node.setProperty("cmiscd:isMajorVersion", pbv.getFirstValue());
    } else {
      node.setProperty("cmiscd:isMajorVersion", (String) null);
    }
    pbv = item.getProperty("cmis:isLatestMajorVersion");
    if (pbv != null) {
      node.setProperty("cmiscd:isLatestMajorVersion", pbv.getFirstValue());
    } else {
      node.setProperty("cmiscd:isLatestMajorVersion", (String) null);
    }
    Property<String> psv = item.getProperty("cmis:versionLabel");
    if (psv != null) {
      node.setProperty("cmiscd:versionLabel", psv.getFirstValue());
    } else {
      node.setProperty("cmiscd:versionLabel", (String) null);
    }
    psv = item.getProperty("cmis:versionSeriesId"); // will be used in sync
    if (psv != null) {
      node.setProperty("cmiscd:versionSeriesId", psv.getFirstValue());
    } else {
      node.setProperty("cmiscd:versionSeriesId", (String) null);
    }
    psv = item.getProperty("cmis:versionSeriesCheckedOutId"); // will be used in
                                                              // sync
    if (psv != null) {
      node.setProperty("cmiscd:versionSeriesCheckedOutId", psv.getFirstValue());
    } else {
      node.setProperty("cmiscd:versionSeriesCheckedOutId", (String) null);
    }
    psv = item.getProperty("cmis:checkinComment");
    if (psv != null) {
      node.setProperty("cmiscd:checkinComment", psv.getFirstValue());
    } else {
      node.setProperty("cmiscd:checkinComment", (String) null);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void initFile(Node localNode,
                          String title,
                          String id,
                          String type,
                          String link,
                          String previewLink,
                          String thumbnailLink,
                          String author,
                          String lastUser,
                          Calendar created,
                          Calendar modified,
                          long size) throws RepositoryException {

    // clarify type: try guess more relevant MIME type from file name/extension.
    String recommendedType = findMimetype(title, type);
    if (recommendedType != null && !recommendedType.equals(type)) {
      type = recommendedType;
    }

    super.initFile(localNode, title, id, type, link, previewLink, thumbnailLink, author, lastUser, created, modified, size);
  }

  /**
   * Initialize CMIS Change Token of a file.<br>
   * Override this method to apply vendor specific logic (id type etc).
   *
   * @param localNode {@link Node}
   * @param changeToken {@link String}
   * @throws RepositoryException the repository exception
   * @throws CMISException the CMIS exception
   */
  protected void setChangeToken(Node localNode, String changeToken) throws RepositoryException, CMISException {
    localNode.setProperty("cmiscd:changeToken", changeToken);
  }

  /**
   * Read CMIS change token of a file.<br>
   * Override this method to apply vendor specific logic (id type etc).
   *
   * @param localNode {@link Node}
   * @return {@link String}
   * @throws RepositoryException the repository exception
   * @throws CMISException the CMIS exception
   */
  protected String getChangeToken(Node localNode) throws RepositoryException, CMISException {
    return localNode.getProperty("cmiscd:changeToken").getString();
  }

  /**
   * Update or create a local node of Cloud File. If the node is
   * <code>null</code> then it will be open on the given parent and created if
   * not already exists.
   * 
   * @param api {@link CMISAPI}
   * @param item {@link CmisObject}
   * @param parent {@link Node}
   * @param node {@link Node}, can be <code>null</code>
   * @return {@link JCRLocalCloudFile}
   * @throws RepositoryException for storage errors
   * @throws CloudDriveException for drive or format errors
   */
  protected JCRLocalCloudFile updateItem(CMISAPI api, CmisObject item, Node parent, Node node) throws RepositoryException,
                                                                                               CloudDriveException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> updateItem: " + item.getId() + " " + item.getName() + " " + item.getType().getDisplayName() + " ("
          + item.getBaseType().getDisplayName() + ", " + item.getBaseTypeId().value() + ")");
    }

    String id = item.getId();
    String name = item.getName();
    String type, typeMode;
    boolean isFolder, isDocument;
    long size;
    if (api.isDocument(item)) {
      isFolder = false;
      isDocument = true;
      Document document = (Document) item;
      size = document.getContentStreamLength();
      type = document.getContentStreamMimeType();
      if (type == null || type.equals(mimeTypes.getDefaultMimeType())) {
        type = mimeTypes.getMimeType(name);
      }
      typeMode = mimeTypes.getMimeTypeMode(type, name);
    } else {
      isDocument = false;
      isFolder = api.isFolder(item);
      size = -1;
      type = item.getType().getId();
      typeMode = null;
    }

    // read/create local node if not given
    if (node == null) {
      if (isFolder) {
        node = openFolder(id, name, parent);
      } else {
        node = openFile(id, name, parent);
      }
    }

    Calendar created = item.getCreationDate();
    Calendar modified = item.getLastModificationDate();
    String createdBy = item.getCreatedBy();
    String modifiedBy = item.getLastModifiedBy();

    boolean changed = node.isNew();
    if (!changed) {
      String changeTokenText = item.getChangeToken();
      String localChangeTokenText;
      try {
        localChangeTokenText = getChangeToken(node);
      } catch (PathNotFoundException e) {
        // not set locally (was null previously for this item)
        localChangeTokenText = null;
      }
      if (changeTokenText == null || localChangeTokenText == null) {
        // if changeToken is null, then we will use last-modified date to decide
        // for local update
        // Feb 13 2015: use equals instead of after as in case of version
        // cancellation file will have date
        // of restored version from the past
        changed = !modified.equals(node.getProperty("ecd:modified").getDate());
      } else {
        changed = !localChangeTokenText.equals(changeTokenText);
      }
    }

    String link, thumbnailLink;
    JCRLocalCloudFile file;
    if (isFolder) {
      link = api.getLink((Folder) item);
      thumbnailLink = null;
      if (changed) {
        initFolder(node, id, name, type, link, createdBy, modifiedBy, created, modified);
        initCMISItem(node, item);
      }
      file = new JCRLocalCloudFile(node.getPath(), id, name, link, type, modifiedBy, createdBy, created, modified, node, true);
    } else {
      link = api.getLink(item);
      thumbnailLink = link;
      if (changed) {
        initFile(node,
                 id,
                 name,
                 type, // mimetype
                 link,
                 null, // embedLink=null
                 thumbnailLink,
                 createdBy,
                 modifiedBy,
                 created,
                 modified,
                 size);
        initCMISItem(node, item);
      }
      file = new JCRLocalCloudFile(node.getPath(),
                                   id,
                                   name,
                                   link,
                                   previewLink(null, node),
                                   thumbnailLink,
                                   type,
                                   typeMode,
                                   createdBy,
                                   modifiedBy,
                                   created,
                                   modified,
                                   size,
                                   node,
                                   changed);
    }

    return file;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String previewLink(String type, Node fileNode) throws RepositoryException {
    return ContentService.contentLink(rootWorkspace, fileNode.getPath(), fileAPI.getId(fileNode));
  }

  /**
   * Find MIME type of given CMIS document. If the document type is
   * <code>null</code> or a default value as defined by
   * {@link ExtendedMimeTypeResolver#getDefaultMimeType()}, then most relevant
   * type will be determined and if nothing found an existing local type will be
   * returned. Otherwise a default type will be returned as defined by
   * {@link ExtendedMimeTypeResolver#getDefaultMimeType()}.
   * 
   * @param file {@link Document} CMIS document
   * @param localType {@link String} locally stored MIME type for given file or
   *          <code>null</code>
   * @return {@link String} relevant MIME type, not <code>null</code>
   */
  protected String findMimetype(Document file, String localType) {
    return findMimetype(file.getName(), file.getContentStreamMimeType(), localType);
  }

  /**
   * Determine a MIME type of given file name if given file type is
   * <code>null</code> or a default value as defined by
   * {@link ExtendedMimeTypeResolver#getDefaultMimeType()}. If required, a MIME
   * type will be guessed by
   * {@link ExtendedMimeTypeResolver#getMimeType(String)} and returned if found.
   * Otherwise a default type will be returned (
   * {@link ExtendedMimeTypeResolver#getDefaultMimeType()}).
   * 
   * @param fileName {@link String} file name
   * @param fileType {@link String} MIME type already associated with the given
   *          file
   * @return {@link String} relevant MIME type, not <code>null</code>
   */
  protected String findMimetype(String fileName, String fileType) {
    return findMimetype(fileName, fileType, null);
  }

  /**
   * Determine a MIME type for given file name if given file type is
   * <code>null</code> or a default value as defined by
   * {@link ExtendedMimeTypeResolver#getDefaultMimeType()}. Otherwise this
   * method returns the given file type.<br>
   * If required, a MIME type will be guessed by
   * {@link ExtendedMimeTypeResolver#getMimeType(String)} and returned if found.
   * If not, and given alternative type not <code>null</code>, the alternative
   * type will be returned. Otherwise a default type will be returned (
   * {@link ExtendedMimeTypeResolver#getDefaultMimeType()}).
   * 
   * @param fileName {@link String} file name
   * @param fileType {@link String} MIME type already associated with the given
   *          file name
   * @param alternativeType {@link String} alternative (locally stored) MIME
   *          type for given file name or <code>null</code>
   * @return {@link String} relevant MIME type, not <code>null</code>
   */
  protected String findMimetype(String fileName, String fileType, String alternativeType) {
    final String defaultType = mimeTypes.getDefaultMimeType();
    if (fileType == null || fileType.startsWith(defaultType)) {
      // try find most relevant MIME type
      String resolvedType = mimeTypes.getMimeType(fileName);
      if (resolvedType != null && !resolvedType.startsWith(defaultType)) {
        fileType = resolvedType;
      } else if (alternativeType != null) {
        fileType = alternativeType;
      } else {
        fileType = defaultType;
      }
    }
    return fileType;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void ensureSame(CloudUser user, Node driveNode) throws RepositoryException, CannotConnectDriveException {
    // additionally check for serviceURL
    super.ensureSame(user, driveNode);

    try {
      String serviceURL = driveNode.getProperty("cmiscd:serviceURL").getString();
      String repositoryId = driveNode.getProperty("cmiscd:repositoryId").getString();

      CMISUser cmisUser = (CMISUser) user;

      if (!repositoryId.equals(cmisUser.api().getRepositoryId())) {
        LOG.warn("Cannot connect drive. Node " + driveNode.getPath() + " was connected to another repository " + repositoryId);
        throw new CannotConnectDriveException("Node already initialized for another repository " + repositoryId);
      }
      if (!serviceURL.equals(cmisUser.api().getServiceURL())) {
        LOG.warn("Cannot connect drive. Node " + driveNode.getPath() + " was connected to another server " + serviceURL);
        throw new CannotConnectDriveException("Node already initialized by another server " + serviceURL);
      }
    } catch (PathNotFoundException e) {
      // if something not found it's not fully initialized drive
      throw new CannotConnectDriveException("Mandatory drive property not found: " + e.getMessage());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void readNodes(Node parent, Map<String, List<Node>> nodes, boolean deep) throws RepositoryException {
    // gather original mappings
    super.readNodes(parent, nodes, deep);

    final FileAPI fileAPI = (FileAPI) this.fileAPI;

    // reconstruct the map in its order but with double mappings for files: by
    // document ID and its
    // version series ID (it is a prefix for SP CMIS)
    Map<String, List<Node>> newNodes = new LinkedHashMap<String, List<Node>>();
    for (Map.Entry<String, List<Node>> me : nodes.entrySet()) {
      for (Node lnode : me.getValue()) {
        if (fileAPI.isFile(lnode)) {
          try {
            String vsid = fileAPI.getVersionSeriesId(lnode);
            newNodes.put(vsid, me.getValue());
          } catch (PathNotFoundException e) {
            // version series id not set
          }
          try {
            String vscoid = fileAPI.getVersionSeriesCheckedOutId(lnode);
            newNodes.put(vscoid, me.getValue());
          } catch (PathNotFoundException e) {
            // version series checked out id not set
          }
        }
        newNodes.put(me.getKey(), me.getValue());
      }
    }

    // replace the map content
    nodes.clear();
    nodes.putAll(newNodes);
  }

  /**
   * Find a node(s) representing a CMIS object with given ID.
   *
   * @param id {@link String}
   * @param file {@link CmisObject} remote file object
   * @param nodes list of existing locally files' nodes in the cloud drive
   * @return list of nodes representing CMIS file with given ID
   * @throws CloudDriveAccessException the cloud drive access exception
   * @throws CMISException the CMIS exception
   * @throws UnauthorizedException the unauthorized exception
   * @see #readNodes(Node, Map, boolean)
   */
  protected List<Node> findDocumentNode(String id,
                                        CmisObject file,
                                        Map<String, List<Node>> nodes) throws CloudDriveAccessException,
                                                                       CMISException,
                                                                       UnauthorizedException {
    List<Node> existing = nodes.get(id);
    if (existing == null) {
      // In CMIS, a document (file) can have several versions, each version has
      // own id
      // when file not found locally by given id, we will get the file all
      // remote versions and try
      // find one of them locally (in given map) and if found return a latest
      // version of this file.
      // By returning latest version of a file we assume that found local file
      // represents it and should be
      // updated to the version state.
      CMISAPI api = getUser().api();
      if (api.isDocument(file)) {
        // try by version series id
        Document document = (Document) file;
        existing = nodes.get(document.getVersionSeriesId());
        if (existing == null) {
          try {
            // try by all file versions' id
            List<Document> versions = api.getAllVersion(document);
            // traverse versions (no PWC there?) and check in local map,
            // return first occurrence (no other should be in theory)
            for (Document v : versions) {
              existing = nodes.get(v.getId());
              if (existing != null) {
                break;
              }
            }
          } catch (NotFoundException e) {
            // cannot find remote versions
            if (LOG.isDebugEnabled()) {
              LOG.debug("Remote file " + id + " (" + file.getName() + ") or its versions cannot be found. " + e.getMessage());
            }
          }
        }
      }
    }
    return existing;
  }

  /**
   * {@inheritDoc}
   */
  public ContentReader getFileContent(String fileId) throws CloudDriveException,
                                                     NotFoundException,
                                                     CloudDriveAccessException,
                                                     DriveRemovedException,
                                                     UnauthorizedException,
                                                     RepositoryException {
    CMISAPI api = getUser().api();
    CmisObject item = api.getObject(fileId);
    if (api.isDocument(item)) {
      Document document = (Document) item;
      String name = document.getName();
      String mimeType = document.getContentStreamMimeType();
      if (mimeType == null || mimeType.startsWith(mimeTypes.getDefaultMimeType())) {
        // try guess the type from name/extension
        String fileType = mimeTypes.getMimeType(name);
        if (fileType != null) {
          mimeType = fileType;
        }
      }
      return new DocumentContent(document.getContentStream(), mimeType, name);
    }
    return null;
  }
}
