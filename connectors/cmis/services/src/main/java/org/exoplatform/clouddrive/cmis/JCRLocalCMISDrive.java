/*
 * Copyright (C) 2003-2013 eXo Platform SAS.
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
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.enums.CapabilityChanges;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudFileAPI;
import org.exoplatform.clouddrive.CloudProviderException;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.ConflictException;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.SyncNotSupportedException;
import org.exoplatform.clouddrive.cmis.CMISAPI.ChangeToken;
import org.exoplatform.clouddrive.cmis.CMISAPI.ChangesIterator;
import org.exoplatform.clouddrive.cmis.CMISAPI.ChildrenIterator;
import org.exoplatform.clouddrive.cmis.CMISConnector.API;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudDrive;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudFile;
import org.exoplatform.clouddrive.jcr.NodeFinder;
import org.exoplatform.commons.utils.MimeTypeResolver;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.gatein.common.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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

/**
 * Local drive for CMIS provider.<br>
 * 
 */
public class JCRLocalCMISDrive extends JCRLocalCloudDrive {

  /**
   * Period to perform {@link FullSync} as a next sync request. See implementation of
   * {@link #getSyncCommand()}.
   */
  public static final long   FULL_SYNC_PERIOD = 24 * 60 * 60 * 60 * 1000; // 24hrs

  public static final String EMPTY_TOKEN      = "".intern();

  /**
   * Connect algorithm for Template drive.
   */
  protected class Connect extends ConnectCommand {

    protected final CMISAPI api;

    protected Connect() throws RepositoryException, DriveRemovedException {
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void fetchFiles() throws CloudDriveException, RepositoryException {
      String changeToken = api.getRepositoryInfo().getLatestChangeLogToken();
      long changeId = System.currentTimeMillis(); // time of the begin

      // Folder root = fetchRoot(rootNode);
      Folder root = api.getRootFolder();
      // actual drive Id (its root folder's id) and URL, see initDrive() also
      rootNode.setProperty("ecd:id", root.getId());
      // TODO api.getRepositoryInfo().getThinClientUri() ?
      rootNode.setProperty("ecd:url", api.getLink(root));

      fetchChilds(root.getId(), rootNode);

      if (!Thread.currentThread().isInterrupted()) {
        initCMISItem(rootNode, root); // init parent

        // set change token rom the start of the connect to let next sync fetch all changes
        setChangeToken(rootNode, changeToken);

        // sync position as current time of the connect start
        setChangeId(changeId);
      }
    }

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
          // TODO do we need this check? parent already known in the context of the fetching - fileId
          // List<Folder> parents = ((FileableCmisObject) item).getParents();
          // if (parents.size() == 0) {
          // // it is undefined item or root folder, in both cases we have nothing to do with it
          // if (LOG.isDebugEnabled()) {
          // LOG.debug("Skipped fileable item without parent: " + item.getId() + " " + item.getName());
          // }
          // } else {
          JCRLocalCloudFile localItem = updateItem(api, item, parent, null);
          if (localItem.isChanged()) {
            changed.add(localItem);
            if (localItem.isFolder()) {
              // go recursive to the folder
              fetchChilds(localItem.getId(), localItem.getNode());
            }
          } else {
            throw new CMISException("Fetched item was not added to local drive storage");
          }
          // }
        }
      }
      return items.parent;
    }
  }

  /**
   * A facade {@link SyncCommand} implementation. This command will choose an actual type of the
   * synchronization depending on the CMIS repository capabilities (Changes Log support).
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
       * Existing files being synchronized with cloud.
       */
      protected final Set<Node> synced = new HashSet<Node>();

      protected void syncFiles() throws CloudDriveException, RepositoryException {
        ChangeToken remoteChangeToken = api.readToken(api.getRepositoryInfo().getLatestChangeLogToken());
        ChangeToken localChangeToken = api.readToken(getChangeToken(rootNode));

        if (!remoteChangeToken.equals(localChangeToken)) {
          @Deprecated
          long lastChangeId = getChangeId(); // TODO not used?

          long changeId = System.currentTimeMillis(); // time of the begin

          // TODO in general it can be possible that Changes Log capability will be disabled in runtime and
          // then remoteChangeToken can be null
          changes = api.getChanges(localChangeToken);
          iterators.add(changes);

          if (changes.hasNext()) {
            readLocalNodes(); // read all local nodes to nodes list
            CmisObject previousItem = null;
            ChangeEvent previousEvent = null;
            Set<String> previousParentIds = null;
            while (changes.hasNext() && !Thread.currentThread().isInterrupted()) {
              CmisObject item = null;
              Set<String> parentIds = new LinkedHashSet<String>();

              ChangeEvent change = changes.next();
              ChangeType changeType = change.getChangeType();
              String id = change.getObjectId();

              // use change.getProperties() to try get the object type and process document/folder only
              if (api.isSyncableChange(change)) {
                if (!ChangeType.DELETED.equals(changeType)) {
                  try {
                    item = api.getObject(change.getObjectId());
                  } catch (NotFoundException e) {
                    // object not found on the server side, it could be removed during the fetch or CMIS
                    // implementation applies trashing (move to vendor specific Trash on deletion) -
                    // delete file locally
                    if (LOG.isDebugEnabled()) {
                      LOG.debug("File " + changeType.value() + " " + id
                          + " not found remotely - apply DELETED logic.");
                    }
                  }
                }

                if (item == null) {
                  // file deleted
                  if (hasRemoved(id)) {
                    cleanRemoved(id);
                    if (LOG.isDebugEnabled()) {
                      LOG.debug(">> Returned file removal " + id);
                    }
                  } else {
                    if (LOG.isDebugEnabled()) {
                      LOG.debug(">> File removal " + id);
                    }
                  }
                  // even in case of local removal we check for all parents to merge possible removals done in
                  // parallel locally and remotely
                  // XXX empty remote parents means item fully removed in the CMIS repo
                  // TODO check if DELETED happes in case of multi-filed file unfiling
                  deleteFile(id, new HashSet<String>());
                } else {
                  // file created/updated
                  String name = item.getName();
                  boolean isFolder = api.isFolder(item);

                  // get parents
                  if (isFolder) {
                    Folder p = ((Folder) item).getFolderParent();
                    if (p != null) {
                      parentIds.add(p.getId());
                    } else {
                      // else it's root folder
                      if (LOG.isDebugEnabled()) {
                        LOG.debug("Found change of folder without parent. Skipping it: " + id + " " + name);
                      }
                      continue;
                    }
                  } else if (api.isRelationship(item)) {
                    if (LOG.isDebugEnabled()) {
                      LOG.debug("Found change of relationship. Skipping it: " + id + " " + name);
                    }
                    continue;
                  } else {
                    // else we have fileable item
                    List<Folder> ps = ((FileableCmisObject) item).getParents(api.folderContext);
                    if (ps.size() == 0) {
                      // item has no parent, it can be undefined item or root folder - we skip it
                      if (LOG.isDebugEnabled()) {
                        LOG.debug("Found change of fileable item without parent. Skipping it: " + id + " "
                            + name);
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
                      LOG.debug(">> Returned file update " + id + " " + name);
                    }
                  } else {
                    if (LOG.isDebugEnabled()) {
                      LOG.debug(">> File update " + id + " " + name);
                    }

                    // XXX nasty workround for Nuxeo versioning on file/folder creation (it sends all versions
                    // in events even for cmis:folder)
                    if (previousItem != null && name.equals(previousItem.getName()) && previousEvent != null
                        && ChangeType.CREATED.equals(previousEvent.getChangeType())
                        && previousParentIds != null && parentIds.containsAll(previousParentIds)) {
                      // same name object on the same parents was created by previous event - we assume this
                      // current as a 'version' of that previous and skip for the moment
                      // TODO apply correct version detection for documents
                      // TODO folder version detection can be done in dedicated connector via native API
                      previousItem = null;
                      previousEvent = null;
                      previousParentIds = null;
                      continue;
                    }

                    updateFile(item, parentIds, isFolder);
                  }
                }
              } // else, skip the change of unsupported object type
              previousItem = item;
              previousEvent = change;
              previousParentIds = parentIds;
            }

            if (!Thread.currentThread().isInterrupted()) {
              // update sync position
              setChangeToken(rootNode, changes.getLastChangeToken().getString()); // TODO remoteChangeToken
              setChangeId(changeId);
            }
          }
        } // else, no new changes
      }

      /**
       * Remove file's node.
       * 
       * @param fileId {@link String}
       * @param parentIds set of Ids of parents (folders)
       * @throws RepositoryException
       */
      protected void deleteFile(String fileId, Set<String> parentIds) throws RepositoryException {
        List<Node> existing = nodes.get(fileId);
        if (existing != null) {
          // remove existing file,
          // also clean the nodes map from the descendants (they can be recorded in following changes)
          for (Iterator<Node> enliter = existing.iterator(); enliter.hasNext();) {
            Node en = enliter.next();
            String enpath = en.getPath();
            Node ep = en.getParent();
            if (fileAPI.isFolder(ep) || fileAPI.isDrive(ep)) {
              String parentId = fileAPI.getId(ep);
              // respect CMIS multi-filing and remove only if no parent exists remotely
              if (!parentIds.contains(parentId)) {
                // this parent doesn't have the file in CMIS repo - remove it with subtree locally
                for (Iterator<List<Node>> ecnliter = nodes.values().iterator(); ecnliter.hasNext();) {
                  List<Node> ecnl = ecnliter.next();
                  if (ecnl != existing) {
                    for (Iterator<Node> ecniter = ecnl.iterator(); ecniter.hasNext();) {
                      Node ecn = ecniter.next();
                      if (ecn.getPath().startsWith(enpath)) {
                        ecniter.remove();
                      }
                    }
                    if (ecnl.size() == 0) {
                      ecnliter.remove();
                    }
                  } // else will be removed below
                }
                removed.add(enpath); // add path to removed
                en.remove(); // remove node
                enliter.remove(); // remove from existing list
              } // else this file filed on this parent in CMIS repo - keep it locally also
            } else {
              LOG.warn("Skipped node with not cloud folder/drive parent: " + enpath);
            }
          }
          if (existing.size() == 0) {
            existing.remove(fileId);
          }
        }
      }

      /**
       * Create or update file's node.
       * 
       * @param item {@link CmisObject}
       * @param parentIds set of Ids of parents (folders)
       * @throws CloudDriveException
       * @throws IOException
       * @throws RepositoryException
       * @throws InterruptedException
       */
      protected void updateFile(CmisObject item, Set<String> parentIds, boolean isFolder) throws CloudDriveException,
                                                                                         RepositoryException {
        String id = item.getId();
        String name = item.getName();
        List<Node> existing = nodes.get(id);

        for (String pid : parentIds) {
          List<Node> fileParents = nodes.get(pid);
          if (fileParents == null) {
            throw new CMISException("Inconsistent changes: cannot find parent Node for " + id + " '" + name
                + "'");
          }

          for (Node fp : fileParents) {
            Node localNode = null;
            Node localNodeCopy = null;
            if (existing == null) {
              existing = new ArrayList<Node>();
              nodes.put(id, existing);
            } else {
              for (Node n : existing) {
                localNodeCopy = n;
                if (n.getParent().isSame(fp)) {
                  localNode = n;
                  break;
                }
              }
            }

            // copy/move existing node
            if (localNode == null) {
              if (isFolder && localNodeCopy != null) {
                // copy from local copy of the folder to a new parent
                localNode = copyNode(localNodeCopy, fp);
              } // otherwise will be created below by updateItem() method

              // create/update node and update CMIS properties
              JCRLocalCloudFile localFile = updateItem(api, item, fp, localNode);
              if (localFile.isChanged()) {
                changed.add(localFile);
              }
              localNode = localFile.getNode();
              // add created/copied Node to list of existing
              existing.add(localNode);
            } else if (!fileAPI.getTitle(localNode).equals(name)) {
              // file was renamed, rename (move) its Node also
              JCRLocalCloudFile localFile = updateItem(api, item, fp, moveFile(id, name, localNode, fp));
              if (localFile.isChanged()) {
                changed.add(localFile);
              }
              localNode = localFile.getNode();
            }

            synced.add(localNode);
          }
        }

        if (existing != null) {
          // need remove other existing (not listed in changes parents)
          for (Iterator<Node> niter = existing.iterator(); niter.hasNext();) {
            Node n = niter.next();
            if (!synced.contains(n)) {
              removed.add(n.getPath());
              niter.remove();
              n.remove();
            }
          }
        }
      }
    }

    /**
     * CMIS drive sync based on all remote files traversing: we do
     * compare all remote files with locals by its change log and fetch an item if the logs differ.
     */
    protected class TraversingAlgorithm {

      protected class CMISItem {
        protected final CmisObject object;

        protected final String     parentId;

        protected CMISItem(CmisObject object, String parentId) {
          super();
          this.object = object;
          this.parentId = parentId;
        }
      }

      protected class FolderReader implements Callable<Folder> {
        protected final String folderId;

        protected FolderReader(String folderId) {
          super();
          this.folderId = folderId;
        }

        /**
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

      protected final Map<String, List<Node>> allLocal = new HashMap<String, List<Node>>();

      protected final Queue<CMISItem>         allItems = new ConcurrentLinkedQueue<CMISItem>();

      protected final Queue<Future<Folder>>   readers  = new ConcurrentLinkedQueue<Future<Folder>>();

      protected Future<Folder> readItems(String folderId) throws RepositoryException, CloudDriveException {

        // start read items in another thread
        Future<Folder> reader = workerExecutor.submit(new FolderReader(folderId));
        readers.add(reader);
        return reader;
      }

      protected void syncFiles() throws RepositoryException, CloudDriveException, InterruptedException {
        String changeToken = api.getRepositoryInfo().getLatestChangeLogToken(); // can be null
        String localChangeToken = getChangeToken(rootNode); // can be EMPTY_TOKEN, this means it was null
        long changeId = System.currentTimeMillis(); // time of the begin

        if (!localChangeToken.equals(EMPTY_TOKEN) && localChangeToken.equals(changeToken)) {
          // already up to date, only set sync position as current time of the sync start
          if (!Thread.currentThread().isInterrupted()) {
            setChangeId(changeId);
          }
        } else {
          // real all local nodes of this drive
          readLocalNodes();

          // copy all drive map to use in // sync
          for (Map.Entry<String, List<Node>> ne : nodes.entrySet()) {
            allLocal.put(ne.getKey(), new ArrayList<Node>(ne.getValue())); // copy lists !
          }

          // sync with cloud
          Folder root = syncChilds(api.getRootFolder().getId());

          // remove local nodes of files not existing remotely, except of root
          nodes.remove(root.getId());
          for (Iterator<List<Node>> niter = nodes.values().iterator(); niter.hasNext()
              && !Thread.currentThread().isInterrupted();) {
            List<Node> nls = niter.next();
            next: for (Node n : nls) {
              String npath = n.getPath();
              for (String rpath : removed) {
                if (npath.startsWith(rpath)) {
                  continue next;
                }
              }
              removed.add(npath);
              n.remove();
            }
          }

          if (!Thread.currentThread().isInterrupted()) {
            initCMISItem(rootNode, root); // init parent

            // set change token rom the start of the connect to let next sync fetch all changes
            setChangeToken(rootNode, changeToken);

            // sync position as current time of the connect start
            setChangeId(changeId);
          }
          allLocal.clear();
          allItems.clear();
          readers.clear();
        }
      }

      @Deprecated
      protected Folder syncChildsOld(final String folderId, final Node parent, final Queue<Future<?>> workers) throws RepositoryException,
                                                                                                              CloudDriveException,
                                                                                                              InterruptedException {

        // TODO will api return multi-filed file in each related folder?
        ChildrenIterator items = api.getFolderItems(folderId);
        iterators.add(items);
        while (items.hasNext() && !Thread.currentThread().isInterrupted()) {
          CmisObject item = items.next();
          if (api.isRelationship(item)) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Skipped relationship object: " + item.getId() + " " + item.getName());
            }
          } else {
            // TODO do we need check for unfilled files
            // List<Folder> parents = ((FileableCmisObject) item).getParents(api.folderContext); // this call
            // goes to CMIS server
            // if (parents.size() == 0) {
            // // it is undefined item or root folder, in both cases we have nothing to do with it
            // if (LOG.isDebugEnabled()) {
            // LOG.debug("Skipped fileable item without parent: " + item.getId() + " " + item.getName());
            // }
            // } else {
            JCRLocalCloudFile localItem = updateItem(api, item, parent, null);
            if (localItem.isChanged()) {
              changed.add(localItem);
            }

            // remove this file (or folder subtree) from map of local to mark it as existing,
            // others will be removed in syncFiles() after.
            List<Node> existing = nodes.get(item.getId());
            if (existing != null) {
              String path = localItem.getPath();
              for (Iterator<Node> eiter = existing.iterator(); eiter.hasNext();) {
                Node enode = eiter.next();
                if (enode.getPath().startsWith(path)) {
                  eiter.remove();
                }
              }
              if (existing.size() == 0) {
                nodes.remove(item.getId());
              }
            }

            if (localItem.isFolder()) {
              // go recursive to the folder in another thread
              syncChilds(localItem.getId());
            }
          }
        }
        return items.parent;
      }

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

      protected Folder syncChilds(final String folderId) throws RepositoryException,
                                                        CloudDriveException,
                                                        InterruptedException {

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
                  changed.add(localItem);
                  // maintain drive map with new/updated
                  List<Node> itemList = allLocal.get(localItem.getId());
                  if (itemList == null) {
                    itemList = new ArrayList<Node>();
                    allLocal.put(localItem.getId(), itemList);
                  }
                  itemList.add(localItem.getNode());
                }
                // remove this file (or folder subtree) from map of local to mark it as existing,
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
              allItems.add(item);
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
    protected final CMISAPI api;

    protected CMISException preSyncError = null;

    protected Sync() {
      super();
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void preSyncFiles() throws CloudDriveException, RepositoryException, InterruptedException {
      // run preparation in try catch for CMIS errors, if error happen we remember it for syncFiles() method,
      // where a full sync will be initiated to fix the erroneous drive state
      try {
        super.preSyncFiles();
      } catch (CMISException e) {
        this.preSyncError = e;
        // We log the error and try fix the drive consistency by full sync (below).
        LOG.warn("Synchronization error: failed to apply local changes to CMIS repository. "
            + "Full sync will be initiated for " + title(), e);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void syncFiles() throws CloudDriveException, RepositoryException, InterruptedException {
      if (preSyncError == null) {
        try {
          RepositoryInfo repoInfo = getUser().api().getRepositoryInfo();
          if (CapabilityChanges.NONE != repoInfo.getCapabilities().getChangesCapability()) {
            // use algorithm based on CMIS Change Log
            new ChangesAlgorithm().syncFiles();
            return;
          } else {
            LOG.info("CMIS Change Log capability not supported by repository " + repoInfo.getName() + " ("
                + repoInfo.getVendorName() + " " + repoInfo.getProductName() + " "
                + repoInfo.getProductVersion()
                + "). Full synchronization will be used instead of the more efficient based on Change Log. "
                + "Check if it is possible to enable Change Log for your repository.");
          }
        } catch (CMISException e) {
          // We log the error and try fix the drive consistency by full sync (below).
          LOG.warn("Synchronization error: failed to read CMIS Change Log. Full sync will be initiated for "
              + title(), e);
        }
      }

      // by default we use algorithm based on full repository traversing
      LOG.info("Full synchronization initiated instead of the more efficient based on CMIS Change Log: "
          + title());
      new TraversingAlgorithm().syncFiles();
    }
  }

  protected interface LocalFile {
    /**
     * Find an Id of remote parent not containing in locals of the file referenced by given Id.
     * 
     * @param fileId {@link String} file Id
     * @param remoteParents {@link Set} of strings with Ids of remote parents
     * @return String an Id or <code>null</code> if remote parent not found
     * @throws CMISException
     */
    String findRemoteParent(String fileId, Set<String> remoteParents) throws CMISException;
  }

  /**
   * {@link CloudFileAPI} implementation.
   */
  protected class FileAPI extends AbstractFileAPI implements LocalFile {

    /**
     * Internal API.
     */
    protected final CMISAPI api;

    protected FileAPI() {
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String findRemoteParent(String fileId, Set<String> remoteParents) throws CMISException {
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
    public String createFile(Node fileNode,
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
        // XXX we assume name as factor of equality here and make local file to reflect the cloud side
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
          // and erase local file data here
          if (fileNode.hasNode("jcr:content")) {
            fileNode.getNode("jcr:content").setProperty("jcr:data", DUMMY_DATA); // empty data by default
          }
        }
      }

      String id = file.getId();
      String name = file.getName();
      String link = api.getLink(file);
      String embedLink = api.getEmbedLink(file);
      String thumbnailLink = link; // TODO need real thumbnail
      String createdBy = file.getCreatedBy();
      String modifiedBy = file.getLastModifiedBy();
      String type = file.getContentStreamMimeType();

      initFile(fileNode, id, name, type, link, embedLink, //
               thumbnailLink, // downloadLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified);
      initCMISItem(fileNode, file);

      return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String createFolder(Node folderNode, Calendar created) throws CloudDriveException,
                                                                 RepositoryException {

      // TODO create folder logic

      String parentId = getParentId(folderNode);
      String title = getTitle(folderNode);
      Folder folder;
      try {
        folder = api.createFolder(getParentId(folderNode), getTitle(folderNode));
      } catch (ConflictException e) {
        // XXX we assume name as factor of equality here
        CmisObject existing = null;
        ChildrenIterator files = api.getFolderItems(parentId);
        while (files.hasNext()) {
          CmisObject item = files.next();
          if (title.equals(item.getName())) { // TODO use more complex check if required
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

      initFolder(folderNode, id, name, type, //
                 link, // link
                 createdBy, // author
                 modifiedBy, // lastUser
                 created,
                 created); // created as modified here
      initCMISItem(folderNode, folder);
      return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateFile(Node fileNode, Calendar modified) throws CloudDriveException, RepositoryException {

      String id = getId(fileNode);
      CmisObject obj = api.updateObject(getParentId(fileNode), id, getTitle(fileNode), this);
      if (obj != null) {
        if (api.isDocument(obj)) {
          Document file = (Document) obj;
          id = file.getId();
          String name = file.getName();
          String link = api.getLink(file);
          String embedLink = api.getEmbedLink(file);
          String thumbnailLink = link; // TODO need real thumbnail
          String createdBy = file.getCreatedBy();
          String modifiedBy = file.getLastModifiedBy();
          String type = file.getContentStreamMimeType();
          Calendar created = file.getCreationDate();
          modified = file.getLastModificationDate();

          initFile(fileNode, id, name, type, link, embedLink, //
                   thumbnailLink, // downloadLink
                   createdBy, // author
                   modifiedBy, // lastUser
                   created,
                   modified);
          initCMISItem(fileNode, file);
        } else {
          throw new CMISException("Object not a document: " + id + ", " + obj.getName());
        }
      } // else file wasn't changed actually
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateFolder(Node folderNode, Calendar modified) throws CloudDriveException,
                                                                RepositoryException {

      String id = getId(folderNode);
      CmisObject obj = api.updateObject(getParentId(folderNode), id, getTitle(folderNode), this);
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

          initFolder(folderNode, id, name, type, //
                     link, // link
                     createdBy, // author
                     modifiedBy, // lastUser
                     created,
                     modified);
          initCMISItem(folderNode, folder);
        } else {
          throw new CMISException("Object not a folder: " + id + ", " + obj.getName());
        }
      } // else folder wasn't changed actually
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateFileContent(Node fileNode, Calendar modified, String mimeType, InputStream content) throws CloudDriveException,
                                                                                                         RepositoryException {
      // Update existing file content and its metadata.
      Document file = api.updateContent(getId(fileNode), getTitle(fileNode), content, mimeType, this);

      String id = file.getId();
      String name = file.getName();
      String link = api.getLink(file);
      String embedLink = api.getEmbedLink(file);
      String thumbnailLink = link; // TODO need real thumbnail
      String createdBy = file.getCreatedBy();
      String modifiedBy = file.getLastModifiedBy();
      String type = file.getContentStreamMimeType();
      Calendar created = file.getCreationDate();
      modified = file.getLastModificationDate();

      initFile(fileNode, id, name, type, link, embedLink, //
               thumbnailLink, // downloadLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified);
      initCMISItem(fileNode, file);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String copyFile(Node srcFileNode, Node destFileNode) throws CloudDriveException,
                                                               RepositoryException {
      Document file = api.copyDocument(getId(srcFileNode), getParentId(destFileNode), getTitle(destFileNode));

      String id = file.getId();
      String name = file.getName();
      String link = api.getLink(file);
      String embedLink = api.getEmbedLink(file);
      String thumbnailLink = link; // TODO need real thumbnail
      String createdBy = file.getCreatedBy();
      String modifiedBy = file.getLastModifiedBy();
      String type = file.getContentStreamMimeType();
      Calendar created = file.getCreationDate();
      Calendar modified = file.getLastModificationDate();

      initFile(destFileNode, id, name, type, link, embedLink, //
               thumbnailLink, // thumbnailLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified);
      initCMISItem(destFileNode, file);
      return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String copyFolder(Node srcFolderNode, Node destFolderNode) throws CloudDriveException,
                                                                     RepositoryException {
      Folder folder = api.copyFolder(getId(srcFolderNode),
                                     getParentId(destFolderNode),
                                     getTitle(destFolderNode));

      String id = folder.getId();
      String name = folder.getName();
      String link = api.getLink(folder);
      String createdBy = folder.getCreatedBy();
      String modifiedBy = folder.getLastModifiedBy();
      String type = folder.getType().getId();
      Calendar created = folder.getCreationDate();
      Calendar modified = folder.getLastModificationDate();

      initFolder(destFolderNode, id, name, type, //
                 link, // link
                 createdBy, // author
                 modifiedBy, // lastUser
                 created,
                 modified);
      initCMISItem(destFolderNode, folder);
      return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeFile(String id) throws CloudDriveException, RepositoryException {
      api.deleteDocument(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeFolder(String id) throws CloudDriveException, RepositoryException {
      api.deleteFolder(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean trashFile(String id) throws CloudDriveException, RepositoryException {
      throw new CMISException("Trash not supported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean trashFolder(String id) throws CloudDriveException, RepositoryException {
      throw new CMISException("Trash not supported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean untrashFile(Node fileNode) throws CloudDriveException, RepositoryException {
      throw new CMISException("Trash not supported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean untrashFolder(Node folderNode) throws CloudDriveException, RepositoryException {
      throw new CMISException("Trash not supported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTrashSupported() {
      return false;
    }
  }

  protected final MimeTypeResolver mimeTypes         = new MimeTypeResolver();

  protected final AtomicLong       changeIdSequencer = new AtomicLong(0);

  /**
   * @param user
   * @param driveNode
   * @param sessionProviders
   * @throws CloudDriveException
   * @throws RepositoryException
   */
  protected JCRLocalCMISDrive(CMISUser user,
                              Node driveNode,
                              SessionProviderService sessionProviders,
                              NodeFinder finder) throws CloudDriveException, RepositoryException {
    super(user, driveNode, sessionProviders, finder);
    CMISAPI api = user.api();
    saveAccess(driveNode, api.getPassword(), api.getServiceURL(), api.getRepositoryId());
  }

  protected JCRLocalCMISDrive(API apiBuilder,
                              Node driveNode,
                              SessionProviderService sessionProviders,
                              NodeFinder finder) throws RepositoryException, CloudDriveException {
    super(loadUser(apiBuilder, driveNode), driveNode, sessionProviders, finder);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void initDrive(Node driveNode) throws CloudDriveException, RepositoryException {
    super.initDrive(driveNode);

    // use empty values, real values will be set during the drive items fetching
    driveNode.setProperty("ecd:id", "");
    driveNode.setProperty("ecd:url", "");
  }

  /**
   * Load user from the drive Node.
   * 
   * @param apiBuilder {@link API} API builder
   * @param driveNode {@link Node} root of the drive
   * @return {@link CMISUser}
   * @throws RepositoryException
   * @throws CMISException
   * @throws CloudDriveException
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
   * @throws CloudDriveException
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
   * 
   * @throws RefreshAccessException
   */
  @Override
  protected SyncCommand getSyncCommand() throws DriveRemovedException,
                                        SyncNotSupportedException,
                                        RepositoryException {
    return new Sync();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudFileAPI createFileAPI() throws DriveRemovedException,
                                        SyncNotSupportedException,
                                        RepositoryException {
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
  public Object getState() throws DriveRemovedException,
                          CloudProviderException,
                          RepositoryException,
                          RefreshAccessException {
    // TODO return getUser().api().getState();
    // no special state provided for the moment
    return null;
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
   * @param localNode {@link Node}
   * @param item {@link CmisObject}
   * @throws RepositoryException
   * @throws CMISException
   */
  protected void initCMISItem(Node localNode, CmisObject item) throws RepositoryException, CMISException {

    // changeToken used for synchronization
    setChangeToken(localNode, item.getChangeToken());

    // XXX probably useless - seems it is OpenCMIS internal time since last update of caches with the server
    localNode.setProperty("cmiscd:refreshTimestamp", item.getRefreshTimestamp());

    // properties below not actually used by the Cloud Drive,
    // they are just for information available to PLF user
    localNode.setProperty("cmiscd:description", item.getDescription());
  }

  /**
   * Initialize CMIS Change Token of a file.<br>
   * Override this method to apply vendor specific logic (id type etc).
   * 
   * @param localNode {@link Node}
   * @param changeToken {@link String}
   * @throws RepositoryException
   * @throws CMISException
   */
  protected void setChangeToken(Node localNode, String changeToken) throws RepositoryException, CMISException {
    localNode.setProperty("cmiscd:changeToken", changeToken != null ? changeToken : EMPTY_TOKEN);
  }

  /**
   * Read CMIS change token of a file.<br>
   * Override this method to apply vendor specific logic (id type etc).
   * 
   * @param localNode {@link Node}
   * @return {@link String}
   * @throws RepositoryException
   * @throws CMISException
   */
  protected String getChangeToken(Node localNode) throws RepositoryException, CMISException {
    return localNode.getProperty("cmiscd:changeToken").getString();
  }

  /**
   * Update or create a local node of Cloud File. If the node is <code>null</code> then it will be open on the
   * given parent and created if not already exists.
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
      LOG.debug(">> updateItem: " + item.getName() + " " + item.getType().getDisplayName() + " ("
          + item.getBaseType().getDisplayName() + ", " + item.getBaseTypeId().value() + ")");
    }

    String id = item.getId();
    String name = item.getName();
    String type;
    boolean isFolder;
    long contentLength;
    if (api.isDocument(item)) {
      isFolder = false;
      Document document = (Document) item;
      contentLength = document.getContentStreamLength();
      type = document.getContentStreamMimeType();
      if (type == null) {
        type = mimeTypes.getMimeType(name);
      }
    } else {
      isFolder = api.isFolder(item);
      contentLength = -1;
      type = item.getType().getId();
    }

    // read/create local node if not given
    if (node == null) {
      if (isFolder) {
        node = openFolder(id, name, parent);
      } else {
        node = openFile(id, name, type, parent);
      }
    }

    Calendar created = item.getCreationDate();
    Calendar modified = item.getLastModificationDate();
    String createdBy = item.getCreatedBy();
    String modifiedBy = item.getLastModifiedBy();

    boolean changed = node.isNew();
    if (!changed) {
      String changeToken = item.getChangeToken();
      if (changeToken == null) {
        // XXX if changeToken is null, then we will use last-modified date to decide for local update
        // TODO try use checksums or hashes, to find status of changes
        changed = modified.after(node.getProperty("ecd:modified").getDate());
      } else {
        changed = !getChangeToken(node).equals(changeToken);
      }
    }

    String link, embedLink, thumbnailLink;
    if (isFolder) {
      link = embedLink = api.getLink((Folder) item);
      thumbnailLink = null;
      if (changed) {
        initFolder(node, id, name, type, link, createdBy, modifiedBy, created, modified);
        initCMISItem(node, item);
      }
    } else {
      link = api.getLink(item);
      embedLink = api.getEmbedLink(item);
      // TODO use real thumbnailLink if available or null
      thumbnailLink = null;
      if (changed) {
        initFile(node, id, name, type, // mimetype
                 link,
                 embedLink,
                 thumbnailLink,
                 createdBy,
                 modifiedBy,
                 created,
                 modified);
        initCMISItem(node, item);

        if (contentLength >= 0) {
          // File size
          // TODO exo's property to show the size: jcr:content's length?
          node.setProperty("cmiscd:size", contentLength);
        }
      }
    }

    return new JCRLocalCloudFile(node.getPath(),
                                 id,
                                 name,
                                 link,
                                 editLink(link),
                                 embedLink,
                                 thumbnailLink,
                                 type,
                                 createdBy,
                                 modifiedBy,
                                 created,
                                 modified,
                                 isFolder,
                                 node,
                                 changed);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String previewLink(String link) {
    // TODO return specially formatted preview link or using a special URL if that required by the cloud API
    return link;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String editLink(String link) {
    // TODO Return actual link for embedded editing (in iframe) or null if that not supported
    return null;
  }
}
