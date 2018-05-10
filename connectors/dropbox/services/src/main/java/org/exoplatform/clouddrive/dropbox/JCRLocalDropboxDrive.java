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

import com.dropbox.core.DbxDownloader;
import com.dropbox.core.v2.files.DeletedMetadata;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.sharing.SharedLinkMetadata;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudFile;
import org.exoplatform.clouddrive.CloudFileAPI;
import org.exoplatform.clouddrive.CloudProviderException;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.ConflictException;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.LocalFileNotFoundException;
import org.exoplatform.clouddrive.NotAcceptableException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.RetryLaterException;
import org.exoplatform.clouddrive.SyncNotSupportedException;
import org.exoplatform.clouddrive.dropbox.DropboxAPI.ListFolder;
import org.exoplatform.clouddrive.dropbox.DropboxConnector.API;
import org.exoplatform.clouddrive.dropbox.JCRLocalDropboxDrive.DropboxDrive.LocalItem;
import org.exoplatform.clouddrive.dropbox.JCRLocalDropboxDrive.MetadataInfo;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudDrive;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudFile;
import org.exoplatform.clouddrive.jcr.NodeFinder;
import org.exoplatform.clouddrive.oauth2.UserToken;
import org.exoplatform.clouddrive.oauth2.UserTokenRefreshListener;
import org.exoplatform.clouddrive.rest.ContentService;
import org.exoplatform.clouddrive.utils.ChunkIterator;
import org.exoplatform.clouddrive.utils.ExtendedMimeTypeResolver;
import org.exoplatform.clouddrive.viewer.CloudFileContent;
import org.exoplatform.clouddrive.viewer.ContentReader;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.jcr.InvalidItemStateException;
import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;

/**
 * Local drive for Dropbox provider.<br>
 * 
 */
public class JCRLocalDropboxDrive extends JCRLocalCloudDrive implements UserTokenRefreshListener {

  /** The Constant FOLDER_REV. */
  public static final String FOLDER_REV  = "".intern();

  /** The Constant FOLDER_TYPE. */
  public static final String FOLDER_TYPE = "folder".intern();

  /**
   * Applicable changes of local Drobpox drive.
   */
  protected interface Changes {

    /**
     * Process locally applied file (it can be any extra operations including the gathering of effected
     * files/stats or chunk saving in JCR).
     *
     * @param changedFile {@link JCRLocalCloudFile} changed file
     * @throws RepositoryException the repository exception
     * @throws CloudDriveException the cloud drive exception
     */
    default void apply(JCRLocalCloudFile changedFile) throws RepositoryException, CloudDriveException {
      // do nothing by default
    }

    /**
     * Undo locally applied file or remove existing.
     *
     * @param changedFile the changed file
     * @throws RepositoryException the repository exception
     * @throws CloudDriveException the cloud drive exception
     */
    default void undo(JCRLocalCloudFile changedFile) throws RepositoryException, CloudDriveException {
      // do nothing by default
    }

    /**
     * Adds the removed path to the scope of changes. For a command {@link AbstractCommand} it effectively
     * should add the path to its removed list.
     *
     * @param path the path
     * @return true, if successful
     * @throws RepositoryException the repository exception
     * @throws CloudDriveException the cloud drive exception
     */
    default boolean removed(String path) throws RepositoryException, CloudDriveException {
      // do nothing by default
      return false;
    }

    /**
     * Answers if given file ID under its parent (by ID) already applied locally.
     * 
     * @param parentId {@link String}
     * @param fileId {@link String}
     * @return boolean, <code>true</code> if file was already applied, <code>false</code> otherwise.
     */
    default boolean canApply(String parentId, String fileId) {
      return true; // can apply any by default
    }
  }

  /**
   * Connect algorithm for Drobpox drive.
   */
  protected class Connect extends ConnectCommand implements Changes {

    /** The api. */
    protected final DropboxAPI api;

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
      long changeId = System.currentTimeMillis(); // time of the begin

      // Obtain initial cursor before the actual fetch of cloud files,
      // this will provide us a proper cursor to start sync from later.
      // We need a recursive cursor to use later with /list_folder/continue in EventsSync
      String driveCursor = api.getLatestCursor(DropboxAPI.ROOT_PATH_V2, true);
      // Make initial list and then fetch the subtree
      ListFolder ls = fetchSubtree(api, DropboxAPI.ROOT_PATH_V2, driveNode, iterators, this);
      iterators.add(ls);

      if (!Thread.currentThread().isInterrupted()) {
        // sync stream
        setChangeId(changeId);
        driveNode.setProperty("dropbox:cursor", driveCursor);
        driveNode.setProperty("dropbox:version", DropboxAPI.VERSION); // used since upgrade to V2
        updateState(ls.getCursor());
      }
    }

    /**
     * Execute Connect from current thread.
     *
     * @throws CloudDriveException the cloud drive exception
     * @throws RepositoryException the repository exception
     */
    protected void execLocal() throws CloudDriveException, RepositoryException {
      // XXX we need this to be able run it from EventsSync.syncFiles()

      commandEnv.configure(this);

      super.exec();

      // at this point we know all changes already applied - we don't need history anymore
      fileHistory.clear();
      try {
        jcrListener.disable();
        String empty = "".intern();
        driveNode.setProperty("ecd:localHistory", empty);
        driveNode.setProperty("ecd:localChanges", empty);
        driveNode.save();
      } catch (Throwable e) {
        LOG.error("Error cleaning local history in " + title(), e);
      } finally {
        jcrListener.enable();
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void apply(JCRLocalCloudFile local) throws RepositoryException, CloudDriveException {
      if (local.isChanged()) {
        String parentIdPath = fileAPI.getParentId(local.getNode());
        addConnected(parentIdPath, local);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void undo(JCRLocalCloudFile local) throws RepositoryException, CloudDriveException {
      if (local.isChanged()) {
        removeChanged(local);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canApply(String parentId, String fileId) {
      return !isConnected(parentId, fileId);
    }
  }

  /**
   * The Class MovedFile.
   */
  protected class MovedFile {

    /**
     * Destination node JCR path.
     */
    protected final String jcrPath;

    /**
     * Expiration time.
     */
    protected final long   expirationTime;

    /**
     * Instantiates a new moved file.
     *
     * @param jcrPath the jcr path
     */
    protected MovedFile(String jcrPath) {
      super();
      this.jcrPath = jcrPath;
      // XXX 10sec to outdate - it is actually a nasty thing that may cause troubles, but we don't have a
      // better solution as for now (May 7, 2018 15sec -> 10sec)
      this.expirationTime = System.currentTimeMillis() + 10000;
    }

    /**
     * Checks if is not outdated.
     *
     * @return true, if is not outdated
     */
    protected boolean isNotOutdated() {
      return this.expirationTime >= System.currentTimeMillis();
    }

    /**
     * Checks if is outdated.
     *
     * @return true, if is outdated
     */
    protected boolean isOutdated() {
      return this.expirationTime < System.currentTimeMillis();
    }
  }

  /**
   * {@link CloudFileAPI} implementation for Dropbox synchronization.
   */
  protected class FileAPI extends AbstractFileAPI {

    /**
     * Internal API.
     */
    protected final DropboxAPI api;

    /**
     * Instantiates a new file API.
     */
    FileAPI() {
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile createFile(Node fileNode, Calendar created, Calendar modified, String mimeType, InputStream content) throws CloudDriveException,
                                                                                                                          RepositoryException {
      normalizeName(fileNode);

      // Create means upload a new file
      return uploadFile(fileNode, created, modified, mimeType, content, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile createFolder(Node folderNode, Calendar created) throws CloudDriveException, RepositoryException {
      normalizeName(folderNode);

      String dbxParentPath = getDropboxPath(folderNode.getParent());
      String title = getTitle(folderNode);
      FolderInfo folder;
      try {
        folder = itemInfo(api.createFolder(dbxParentPath, title)).asFolder();
      } catch (ConflictException e) {
        // Check what exists on this Dropbox path
        String cpath = api.filePath(dbxParentPath, title);
        MetadataInfo existing = itemInfo(api.get(cpath));
        if (existing.isFolder()) {
          folder = existing.asFolder();
        } else {
          throw e; // we cannot do anything at this level
        }
      }

      // Date created ignored here and will be set to ones from Dropdown side
      JCRLocalCloudFile localFile = updateItem(api, folder, null, folderNode);
      return localFile;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile updateFile(Node fileNode, Calendar modified) throws CloudDriveException, RepositoryException {
      MetadataInfo item = move(fileNode);
      if (item != null) {
        return updateItem(api, item, null, fileNode);
      } else {
        // else file don't need to be moved - return local
        return readFile(fileNode);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile updateFolder(Node folderNode, Calendar modified) throws CloudDriveException, RepositoryException {
      MetadataInfo item = move(folderNode);
      if (item != null) {
        return updateItem(api, item, null, folderNode);
      } else {
        // else folder don't need to be moved - return local
        return readFile(folderNode);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile updateFileContent(Node fileNode, Calendar modified, String mimeType, InputStream content) throws CloudDriveException,
                                                                                                               RepositoryException {
      // Update means upload of a new content
      return uploadFile(fileNode, null, modified, mimeType, content, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile copyFile(Node srcFileNode, Node destFileNode) throws CloudDriveException, RepositoryException {
      MetadataInfo item = copy(srcFileNode, destFileNode);
      return updateItem(api, item, null, destFileNode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile copyFolder(Node srcFolderNode, Node destFolderNode) throws CloudDriveException, RepositoryException {
      MetadataInfo item = copy(srcFolderNode, destFolderNode);
      JCRLocalCloudFile localFile = updateItem(api, item, null, destFolderNode);
      if (localFile.isFolder()) {
        // need reset locally copied sub-tree for right Dropbox properties (sharing etc.)
        updateSubtree(localFile.getNode());
      }
      return localFile;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeFile(String id) throws CloudDriveException, RepositoryException {
      MetadataInfo item = itemInfo(api.delete(id));
      return item.isDeleted();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeFolder(String id) throws CloudDriveException, RepositoryException {
      MetadataInfo item = itemInfo(api.delete(id));
      return item.isDeleted();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean trashFile(String id) throws CloudDriveException, RepositoryException {
      return removeFile(id); // remove in Dropbox, it will be possible to restore by file rev
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean trashFolder(String id) throws CloudDriveException, RepositoryException {
      return removeFolder(id); // remove in Dropbox, it will be possible to restore folder file by file
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile untrashFile(Node fileNode) throws CloudDriveException, RepositoryException {
      String dbxPath = getDropboxPath(fileNode);
      String rev = getRev(fileNode);
      MetadataInfo item = itemInfo(api.restoreFile(dbxPath, rev));

      return updateItem(api, item, null, fileNode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile untrashFolder(Node folderNode) throws CloudDriveException, RepositoryException {
      // Untrash folder from local state and do recursively. This seems cannot work for empty folders.
      // Traverse over the folder children nodes (files and folders recursively) and restore file by file.

      // Get the parent folder from cloud side, then untrash its subtree from local state
      JCRLocalCloudFile localFile;
      String id = getId(folderNode);
      // Try get the folder from Dropbox, fetch deleted also to know if it was deleted remotely
      MetadataInfo item = itemInfo(api.get(id, true));
      // For Deleted and existing Folder, all deleted childs will be restored in for-loop below
      if (item.isDeleted()) {
        // re-create this folder in Dropbox and untrash its childs recursively
        item = itemInfo(api.createFolder(item.getParentPath(), getTitle(folderNode)));
        localFile = updateItem(api, item.asItem(), null, folderNode);
      } else if (item.isFolder()) {
        // folder already exists remotely, just update it locally (dbxPath, dates etc) then untrash its childs
        localFile = updateItem(api, item.asItem(), null, folderNode);
      } else if (item.isFile()) {
        // Unexpected & unreal state: it's not deleted and not folder, but a file with the folder ID.
        // But we want stay consistent with a cloud side, thus restore from the remote state
        LOG.warn("Found file with an ID of untrahsed folder: " + id + " path: " + item.getPath());
        // updateItem will replace the folder node with a file according the item type
        localFile = updateItem(api, item.asFile(), null, folderNode);
      } else {
        // This should not happen in theory, but we keep local drive in sync with the remote state - this ex
        // will remove the local node
        LOG.warn("Found unrecognized item type with ID of untrahsed folder: " + id + " path: " + item.getPath() + " item: " + item);
        throw new NotFoundException("Unrecognized item with ID of untrahsed folder");
      }

      if (localFile != null && localFile.isFolder()) {
        // Sub-folders will be restored automatically with files.
        for (NodeIterator children = folderNode.getNodes(); children.hasNext();) {
          Node childNode = children.nextNode();
          if (isFolder(childNode)) {
            // it's folder
            untrashFolder(childNode);
          } else if (isFile(childNode)) {
            // it's file
            untrashFile(childNode);
          } else {
            // remove not recognized nodes
            removeNode(childNode);
          }
        }
      }

      return localFile;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTrashSupported() {
      // FYI Dropbox doesn't have a traditional trash, but it has revisions for the files and each file can be
      // restored to some revision. Their UI allows to see removed folders and files on the site, but the API
      // allows only files restoration.
      // This feature considered as not complete, as for traditional trash, and not used for trashing in eXo.
      // To be reviewed in future.
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile restore(String id, String nodePath) throws CloudDriveException, RepositoryException {

      // FYI info build on lower-case path (name and/or parent path can be inaccurate)
      if (DropboxAPI.ROOT_PATH_V2.equals(id)) {
        // skip root node - this shouldn't happen
        if (LOG.isDebugEnabled()) {
          LOG.debug("Cannot restore root folder - ignore it: " + id + " node: " + nodePath);
        }
        return null;
      } else {
        if (LOG.isDebugEnabled()) {
          LOG.debug(">> restore(" + id + ", " + nodePath + ")");
        }
      }

      // This will contain a last restored item or null if nothing restored
      JCRLocalCloudFile restored = null;

      Session session = session();
      Node node;
      try {
        Item jcrItem = session.getItem(nodePath);
        if (jcrItem.isNode()) {
          node = Node.class.cast(jcrItem);
        } else {
          throw new CloudDriveException("Cannot restore cloud file with not Node path: " + nodePath);
        }
      } catch (PathNotFoundException e) {
        // not found
        node = null;
      }

      DropboxDrive localDrive = new DropboxDrive(api, rootNode());
      if (node != null) {
        // it's restore of update (also move/rename) - we do w/o requesting remove side
        // FYI but in this case we doesn't check the ID change between local and remote
        node = localDrive.restore(node);
        if (node != null) {
          restored = readFile(node); // note: this cloud file will be with *not changed* flag!
        }
      } else {
        // It's restore of removal - we need request remote side for actual state.
        // But first, try find this ID nodes in the local drive by JCR search (at other subtrees).
        // In normal work, this search should find nothing. And it will only if some inconsistency was
        // introduced by this or previous sync.
        Collection<Node> nodes = findNodes(Arrays.asList(id));
        for (Node n : nodes) {
          if (restored == null) {
            // if nothing restored yet, we move this first found to a required location if its Dropbox path
            // already matches - it's a case of something unexpected, but we have a chance to fix it.
            n = localDrive.restore(n);
            if (n != null) {
              restored = readFile(n); // note: this cloud file will be with *not changed* flag!
            }
          } else {
            // everything else should not found
            removeNode(n);
          }
        }
        if (restored == null) {
          String assumedDbxPath = localDrive.getRemotePath(nodePath);
          LocalItem localNode = localDrive.getItem(assumedDbxPath);
          node = localNode.fetch(new Changes() {
            /* everything by default */ });
          if (node != null) {
            restored = readFile(node); // note: this cloud file will be with *not changed* flag!
          } // otherwise, related item already removed at Dropbox
        }
      }
      return restored;
    }

    // ********* internals **********

    /**
     * Gets the rev.
     *
     * @param fileNode the file node
     * @return the rev
     * @throws RepositoryException the repository exception
     */
    protected String getRev(Node fileNode) throws RepositoryException {
      return fileNode.getProperty("dropbox:rev").getString();
    }

    /**
     * Upload file.
     *
     * @param fileNode the file node
     * @param created the created
     * @param modified the modified
     * @param mimeType the mime type
     * @param content the content
     * @param update the update
     * @return the cloud file
     * @throws CloudDriveException the cloud drive exception
     * @throws RepositoryException the repository exception
     */
    protected CloudFile uploadFile(Node fileNode,
                                   Calendar created,
                                   Calendar modified,
                                   String mimeType,
                                   InputStream content,
                                   boolean update) throws CloudDriveException, RepositoryException {

      String parentId = getParentId(fileNode);
      String title = getTitle(fileNode);
      String rev = update ? getRev(fileNode) : null;
      FileInfo file;
      try {
        file = itemInfo(api.uploadFile(parentId, title, content, rev)).asFile();
      } catch (ConflictException e) {
        MetadataInfo item = itemInfo(api.get(api.filePath(parentId, title))); // was fileNode.getName()
        if (item.isFile()) {
          // ignore local file in favor of remote existing at this path
          file = item.asFile();
          // and erase local file data here
          if (fileNode.hasNode("jcr:content")) {
            fileNode.getNode("jcr:content").setProperty("jcr:data", DUMMY_DATA); // empty data by default
          }
        } else {
          throw e; // we cannot do anything at this level
        }
      }

      // Dates, created and modified, ignored here and will be set to ones from Dropdown side
      JCRLocalCloudFile localFile = updateItem(api, file, null, fileNode);
      return localFile;
    }

    /**
     * Move remote file to hierarchy of given node.
     *
     * @param node the node
     * @return the {@link MetadataInfo} instance
     * @throws RepositoryException the repository exception
     * @throws DropboxException the dropbox exception
     * @throws RefreshAccessException the refresh access exception
     * @throws ConflictException the conflict exception
     * @throws NotFoundException the not found exception
     * @throws NotAcceptableException the not acceptable exception
     * @throws RetryLaterException the retry later exception
     */
    protected MetadataInfo move(Node node) throws RepositoryException,
                                           DropboxException,
                                           RefreshAccessException,
                                           ConflictException,
                                           NotFoundException,
                                           NotAcceptableException,
                                           RetryLaterException {
      // Use move for renaming: given node should be moved (incl renamed) to its new parent

      // XXX file when moved in ECMS, can produce several session saves (actual move and then properties
      // updated on the dest) and so it will be several SyncFiles commands, each may be recognized as an
      // update. So, we need refresh the node with other JCR sessions to see its actual state and don't try to
      // repeat a move op.
      node.getSession().refresh(true);

      // Source file ID
      String id = getId(node);
      // Note: since 1.5.1 (1.6.0) the core JCR listener will merge node rename and title property in a
      // single update, at the same time move to different parent will go with a delay but in single update
      // also, thus we don't need tracking moved here (at least for move/rename usecases described above).
      // Ensure node name follows lower-case rule we use for Dropbox local files
      normalizeName(node);

      // Dropbox path of source item (at this moment it contains source path)
      String srcPath = getDropboxPath(node);
      String srcParentPath = parentPath(srcPath);

      Node parent = node.getParent();
      // Remote destination parent ID
      String parentId = getId(parent);
      // Destination parent path
      String destParentPath = getDropboxPath(parent);

      // file title and lower-case name
      String destTitle = getTitle(node);
      String destName = api.lowerCase(destTitle);

      boolean isMove;
      if (!srcParentPath.equals(destParentPath)) {
        // Locally saved Dropbox path's parent isn't the same as file's current parent - it's moved file
        isMove = true;
      } else if (!srcPath.endsWith(destName)) {
        // dbxPath of the file has other than current file name (lower-case!) - it's renamed file
        isMove = true;
      } else {
        // otherwise file wasn't changed in meaning of Dropbox structure
        isMove = false;
      }

      if (isMove) {
        // new Dropbox path for the file (use case preserving title)
        String destPath = api.filePath(parentId, destTitle);

        if (LOG.isDebugEnabled()) {
          LOG.debug("Move remote: " + srcPath + " [" + id + "] -> " + destPath);
        }

        // FYI move conflicts will be solved by the caller (in core)
        MetadataInfo item = itemInfo(api.move(id, destPath));

        if (item.isFolder()) {
          // need update local sub-tree for a right parent in idPaths
          updateSubtree(node);
        }
        return item;
      } else if (LOG.isDebugEnabled()) {
        LOG.debug("Update not moved remote: " + srcPath + " [" + id + "]");
      }
      // } // If file was moved here few seconds ago: return null
      return null;
    }

    /**
     * Update subtree for Dropbox properties saved in the nodes.
     *
     * @param folderNode the folder node
     * @throws RepositoryException the repository exception
     */
    protected void updateSubtree(Node folderNode) throws RepositoryException {
      for (NodeIterator niter = folderNode.getNodes(); niter.hasNext();) {
        Node node = niter.nextNode();
        if (isFile(node)) {
          resetSharing(node);
          if (isFolder(node)) {
            updateSubtree(node);
          }
        }
      }
    }

    /**
     * Reset Dropbox file direct/shared link to force generation of a new one within new dbxPath. It is a
     * required step when copying or moving file - Dropbox maintains links respectively the file location.
     *
     * @param fileNode the file node
     * @throws RepositoryException the repository exception
     */
    protected void resetSharing(Node fileNode) throws RepositoryException {
      // by setting null in JCR we remove the property if it was found
      fileNode.setProperty("dropbox:directLink", (String) null);
      fileNode.setProperty("dropbox:sharedLinkExpires", (String) null);
    }

    /**
     * Copy.
     *
     * @param sourceNode the source node
     * @param destNode the dest node
     * @return the dbx entry
     * @throws RepositoryException the repository exception
     * @throws DropboxException the dropbox exception
     * @throws RefreshAccessException the refresh access exception
     * @throws ConflictException the conflict exception
     * @throws NotFoundException the not found exception
     * @throws RetryLaterException the retry later exception
     * @throws NotAcceptableException the not acceptable exception
     */
    protected MetadataInfo copy(Node sourceNode, Node destNode) throws RepositoryException,
                                                                DropboxException,
                                                                RefreshAccessException,
                                                                ConflictException,
                                                                NotFoundException,
                                                                RetryLaterException,
                                                                NotAcceptableException {
      // It is source, from where we move the file
      String sourceId = getId(sourceNode);

      // it is remote destination parent ID
      String destParentId = getParentId(destNode);

      // file name
      String title = getTitle(destNode);

      // ensure dest node follow the lower-case name rule
      normalizeName(destNode);

      // new Dropbox path for the file (in lower case)
      String destPath = api.filePath(destParentId, title);

      // FYI copy conflicts will be solved by the caller (in core)
      MetadataInfo f = itemInfo(api.copy(sourceId, destPath));

      return f;
    }
  }

  /**
   * An implementation of {@link SyncCommand} based on an abstract deltas queue proposed and maintained by the
   * cloud service.
   */
  protected class EventsSync extends SyncCommand implements Changes {

    /**
     * Internal API.
     */
    protected final DropboxAPI api;

    /**
     * Create command for Template synchronization.
     *
     * @throws RepositoryException the repository exception
     * @throws DriveRemovedException the drive removed exception
     */
    protected EventsSync() throws RepositoryException, DriveRemovedException {
      super();
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void syncFiles() throws CloudDriveException, RepositoryException {
      // We may need full-sync (re-connect) if was connected by API V2
      boolean doSync;
      try {
        doSync = driveNode.getProperty("dropbox:version").getLong() >= DropboxAPI.VERSION;
      } catch (PathNotFoundException e) {
        // need re-connect
        doSync = false;
      }
      if (doSync) {
        // run changes sync: sync algorithm based on list_folder/continue from Dropbox API
        long changeId = System.currentTimeMillis(); // time of the begin

        // It should be a recursive cursor!
        String cursor = driveNode.getProperty("dropbox:cursor").getString();

        try {
          ListFolder ls = api.listFolderContinued(DropboxAPI.ROOT_PATH_V2, cursor);
          iterators.add(ls);

          // FYI we use lower-case rule for Dropbox local file names, it was applied by Connect command
          DropboxDrive localDrive = new DropboxDrive(api, driveNode);

          // Analyze changes to recognize move/rename pairs of Deleted + File/Folder metadata
          // List of changes (aka buffer to recognize move/rename)
          List<MetadataInfo> changes = new LinkedList<>();
          // Tree of found deleted paths, mapping by ordered path of indexes in changes list
          NavigableMap<String, MetadataInfo> deletedPathTree = new TreeMap<>();
          // IDs of items deleted remotely but found locally, mapping by item ID
          Map<String, MetadataInfo> deletedIdMap = new LinkedHashMap<>();
          int appliedIndex = -1;
          while (ls.hasNext() && !Thread.currentThread().isInterrupted()) {
            MetadataInfo change = itemInfo(ls.next());
            if (change.isDeleted()) {
              String deletedPath = change.getPath();
              change.localItem = localDrive.getItem(deletedPath);
              Node node = change.localItem.getNode();
              change.index = changes.size();
              if (node != null) {
                // We'll apply deletes on a new item change (update/create) or end of the changes (see below)
                change.localId = fileAPI.getId(node);
                MetadataInfo prevDeleted = deletedIdMap.put(change.localId, change);
                if (prevDeleted != null) {
                  // this shouldn't happen if we'll remove it within a sub-tree as below
                  if (LOG.isDebugEnabled()) {
                    LOG.debug("Previous deleted found for deleted: " + change + " ID: " + change.localId);
                  }
                  // TODO run deletion of prevDeleted right now?
                }
                // Remove changes that will be done implicitly by the delete (or more/rename) operation
                SortedMap<String, MetadataInfo> deletedSubtree = deletedPathTree.tailMap(deletedPath, false);
                for (Iterator<Map.Entry<String, MetadataInfo>> diter = deletedSubtree.entrySet().iterator(); diter.hasNext();) {
                  // remove all the sub-tree from changes and deleted
                  Map.Entry<String, MetadataInfo> ce = diter.next();
                  if (ce.getKey().startsWith(deletedPath)) {
                    MetadataInfo c = ce.getValue();
                    c.index = -1; // mark as ignored
                    deletedIdMap.remove(c.localId);
                    diter.remove(); // also remove from the tree
                  } else {
                    break; // sub-tree ended
                  }
                }
                // Build deleted tree ordered by path, by a string, thus grouped by items hierarchy
                MetadataInfo prevChange = deletedPathTree.put(deletedPath, change);
                if (prevChange != null) {
                  if (LOG.isDebugEnabled()) {
                    LOG.debug("Path updated in deleted tree: " + change.getPath() + " " + prevChange.index + " -> " + change.index);
                  }
                  // TODO what to do with this change in changes list?
                }
              } else {
                // nothing to delete locally (this may happen when local-to-remote changes get back to us)
                if (LOG.isDebugEnabled()) {
                  LOG.debug("Item not found locally to delete: " + change);
                }
                continue;
              }
            } else if (change.isItem()) {
              change.index = changes.size();
              change.localItem = localDrive.getItem(change.getPath());
              ItemInfo item = change.asItem();
              // From here we may need do following:
              // * recognize a move/rename for the current item
              // * apply all else gathered deletes (in order of appearance)
              // * if not move/rename, then just update the item locally
              MetadataInfo prevDeleted = deletedIdMap.get(item.getId());
              if (prevDeleted != null) {
                // If same location item comes after its ID deletion, it's rename or move
                // deletedPathTree.remove(prevDeleted.getPath(), prevDeleted.index);
                prevDeleted.index = -1; // mark as ignored in changes list
                change.prevDeleted = prevDeleted;
              }
            } else {
              LOG.warn("Unknown update type: " + change);
              continue; // skip this change
            }

            if (LOG.isDebugEnabled()) {
              LOG.debug("Change: " + change + " " + (change.localItem.exists() ? "[found]" : "")
                  + (change.localId != null ? " ID: " + change.localId : ""));
            }

            changes.add(change);

            // If it's update need apply immediately as next change path should be found in the local storage!
            // Check if need apply gathered changes:
            // * if it's item change currently (not deleted)
            // * if have no more changes
            if (change.isItem() || !ls.hasNext()) {
              for (int i = appliedIndex + 1; i < changes.size() && !Thread.currentThread().isInterrupted(); i++) {
                MetadataInfo c = changes.get(i);
                if (c.index >= 0) {
                  if (c.isItem()) {
                    ItemInfo item = c.asItem();
                    // It's item update
                    if (item.prevDeleted != null) {
                      if (LOG.isDebugEnabled()) {
                        LOG.debug("Move: " + item.prevDeleted.getPath() + " -> " + item);
                      }
                      item.prevDeleted.localItem.move(item, this);
                      // XXX move should be immediately saved to allow set JCR properties on dest items
                      save();
                    } else {
                      // It's update of existing or creation of a new
                      if (LOG.isDebugEnabled()) {
                        LOG.debug("Update: " + item);
                      }
                      item.localItem.update(item, this);
                    }
                  } else {
                    // It's item deleted: remove not found or unknown item locally
                    if (LOG.isDebugEnabled()) {
                      LOG.debug("Delete: " + c);
                    }
                    String jcrPath = c.localItem.removeNode();
                    if (jcrPath != null) {
                      addRemoved(jcrPath);
                    }
                  }
                  // Reset the state for move/rename detection
                  deletedPathTree.clear();
                  deletedIdMap.clear();
                } // otherwise, we need skip this change
                appliedIndex = i;
              }
            }
          }
          changes.clear();

          if (!Thread.currentThread().isInterrupted()) {
            setChangeId(changeId);
            // save cursor explicitly to let use it in next sync even if nothing changed in this one
            Property cursorProp = driveNode.setProperty("dropbox:cursor", ls.getCursor());
            cursorProp.save();
            updateState(ls.getCursor());
            if (LOG.isDebugEnabled()) {
              LOG.debug("<< syncFiles: " + driveNode.getPath() + "\n\r" + cursor + " --> " + ls.getCursor());
            }
          }
        } catch (ResetCursorException e) {
          // ResetCursorException means we need full sync (re-connect)
          if (LOG.isDebugEnabled()) {
            LOG.debug("<< syncFiles CURSOR RESET: " + driveNode.getPath() + "\n\r" + cursor, e);
          }
          LOG.warn("Dropbox cursor was reset, need full sync for '" + getTitle() + "'. " + e.getMessage());
          doSync = false;
        }
      } else {
        LOG.warn("Dropbox drive connected by older API, need full sync for '" + getTitle() + "'.");
      }
      if (!doSync) {
        // run re-connect command
        // TODO we need special logic which will check local and remote IDs of files and remove not matched
        // something like a mix of fileAPI.restore() and Connect command.
        new Connect().execLocal();
      }
    }

    /**
     * {@inheritDoc}
     */
    public void apply(JCRLocalCloudFile local) throws RepositoryException, CloudDriveException {
      // XXX we don't take in account changed status here to let returning changes on modifications from this
      // local drive to appear in list of changed in client script and so let refresh the page to reflect
      // rename/move modifications and name normalization (done by move).
      // if (local.isChanged()) {
      removeRemoved(local.getPath());
      addChanged(local);
      // }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void undo(JCRLocalCloudFile local) throws RepositoryException, CloudDriveException {
      if (local.isChanged()) {
        removeChanged(local);
        addRemoved(local.getPath());
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removed(String path) throws RepositoryException, CloudDriveException {
      return addRemoved(path);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canApply(String parentId, String fileId) {
      // in case of sync we assume that any change can be applied
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void preSaveChunk() throws CloudDriveException, RepositoryException {
      // nothing save for sync
    }
  }

  /**
   * The Class DropboxState.
   */
  public class DropboxState extends DriveState {

    /** The cursor. */
    final String cursor;

    /** The url. */
    final String url;

    /** The timeout. */
    final int    timeout;

    /**
     * Instantiates a new dropbox state.
     *
     * @param cursor the cursor
     */
    protected DropboxState(String cursor) {
      this.cursor = cursor;
      this.timeout = DropboxAPI.CHANGES_LONGPOLL_TIMEOUT;
      this.url = DropboxAPI.CHANGES_LONGPOLL_URL;
    }

    /**
     * Gets the cursor.
     *
     * @return the cursor
     */
    public String getCursor() {
      return cursor;
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
     * Gets the timeout.
     *
     * @return the timeout
     */
    public int getTimeout() {
      return timeout;
    }
  }

  /**
   * Dropbox item helper to extract info from its metadata object.
   */
  protected abstract class MetadataInfo {

    /**
     * Hierarchical parent info (without item ID in {@link #getId()}).
     */
    protected final class ParentInfo extends FolderInfo {

      final String itemPath;

      final String itemPathDisplay;

      /**
       * Instantiates a new parent info.
       *
       * @param itemPath the item Dropbox path
       * @param itemPathDisplay the item dbxPath display
       */
      protected ParentInfo(String itemPath, String itemPathDisplay) {
        super(null);
        this.itemPath = itemPath;
        this.itemPathDisplay = itemPathDisplay;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public String toString() {
        return new StringBuilder("ParentInfo [").append(getPath()).append(']').toString();
      }

      /**
       * {@inheritDoc}
       */
      @Override
      protected FileInfo asFile() {
        throw new IllegalArgumentException("Parent folder is not a file: " + getPath());
      }

      /**
       * {@inheritDoc}
       */
      @Override
      protected String getId() {
        throw new IllegalArgumentException("ParentInfo has not ID");
      }

      /**
       * {@inheritDoc}
       */
      @Override
      protected String getTitle() {
        if (pathDisplay == null) {
          pathDisplay = splitPath(getPathDisplay());
        }
        return pathDisplay[1];
      }

      /**
       * {@inheritDoc}
       */
      @Override
      protected String getPath() {
        return itemPath;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      protected String getPathDisplay() {
        return itemPathDisplay;
      }
    }

    String[]     pathDisplay, path;

    /** Index in list of changes. Used when syncing folder changes. */
    int          index       = -1;

    /** The item found locally. Used when syncing folder changes. */
    LocalItem    localItem   = null;

    /** The ID of this item found locally. Used when syncing folder changes. */
    String       localId     = null;

    /** The previously deleted change associated with this item. Used when syncing folder changes. */
    MetadataInfo prevDeleted = null;

    /**
     * Split given Dropbox path on parent path and a name.
     *
     * @param path the path
     * @return the arrays of strings with parent path and name, parent can be <code>null</code> if it's
     *         root
     */
    protected String[] splitPath(String path) {
      String parentPath, name;
      if (DropboxAPI.ROOT_PATH_V2.equals(path) || (path.length() == 1 && path.charAt(0) == '/')) {
        // it's root of the drive
        parentPath = null;
        name = DropboxAPI.ROOT_PATH_V2;
      } else {
        int parentEndIndex = path.lastIndexOf('/');
        int endIndex;
        if (parentEndIndex > 0 && parentEndIndex == path.length() - 1) {
          // for cases with ending slash (e.g. '/my/path/' and we need '/my' parent)
          endIndex = parentEndIndex;
          parentEndIndex = path.lastIndexOf('/', endIndex - 1);
        } else {
          endIndex = path.length();
        }
        if (parentEndIndex > 0) {
          parentPath = path.substring(0, parentEndIndex);
          int nameIndex = parentEndIndex + 1;
          if (nameIndex < endIndex) {
            name = path.substring(nameIndex, endIndex);
          } else {
            name = null;
          }
        } else if (parentEndIndex == 0) {
          // it's root node as parent
          parentPath = DropboxAPI.ROOT_PATH_V2;
          name = path.substring(parentEndIndex + 1, endIndex);
        } else {
          // This should not happen as path has '/', but: we guess it's root node as parent and given
          // path is a name
          parentPath = DropboxAPI.ROOT_PATH_V2;
          name = path;
        }
      }
      return new String[] { parentPath, name };
    }

    /**
     * Gets the parent path display.
     *
     * @return the parent path display
     */
    protected final String getParentPathDisplay() {
      if (pathDisplay == null) {
        pathDisplay = splitPath(getPathDisplay());
      }
      return pathDisplay[0];
    }

    /**
     * Gets the parent path (in lower case as used in SDK).
     *
     * @return the parent path in lower case
     */
    protected final String getParentPath() {
      if (path == null) {
        path = splitPath(getPath());
      }
      return path[0];
    }

    /**
     * Gets the name (in lower case as in SDK path). It can differ with {@link #getTitle()}.
     *
     * @return the name
     */
    protected final String getName() {
      if (path == null) {
        path = splitPath(getPath());
      }
      return path[1];
    }

    /**
     * Return a location of this item parent (but w/o item ID). This method should be used with limitation,
     * only for back-lookup purpose where searching existing local ancestor node by Dropbox path.
     * This {@link ItemInfo} cannot be used for saving in local storage!
     *
     * @see EventsSync#readNode(MetadataInfo)
     * @return the {@link ParentInfo} instance
     */
    protected ParentInfo parent() {
      return new ParentInfo(getParentPath(), getParentPathDisplay());
    }

    /**
     * As file.
     *
     * @return the file info
     */
    protected abstract FileInfo asFile();

    /**
     * As folder.
     *
     * @return the folder info
     */
    protected abstract FolderInfo asFolder();

    /**
     * As item.
     *
     * @return the item info
     */
    protected abstract ItemInfo asItem();

    /**
     * Checks if is root.
     *
     * @return true, if is root
     */
    protected final boolean isRoot() {
      return DropboxAPI.ROOT_PATH_V2.equals(getPath());
    }

    /**
     * Checks if is folder.
     *
     * @return true, if is folder or root of the drive
     */
    protected abstract boolean isFolder();

    /**
     * Checks if is file.
     *
     * @return true, if is file
     */
    protected abstract boolean isFile();

    /**
     * Checks if is item.
     *
     * @return true, if is item
     */
    protected abstract boolean isItem();

    /**
     * Checks if is deleted.
     *
     * @return true, if is deleted
     */
    protected abstract boolean isDeleted();

    /**
     * Gets the title displayed to users.
     *
     * @return the title
     */
    protected abstract String getTitle();

    /**
     * Gets the lower path.
     *
     * @return the lower path
     */
    protected abstract String getPath();

    /**
     * Gets the display path.
     *
     * @return the display path
     */
    protected abstract String getPathDisplay();

  }

  /**
   * The Class UnknownInfo for not recognized changes.
   */
  protected final class UnknownInfo extends MetadataInfo {

    final Metadata metadata;

    UnknownInfo(Metadata metadata) {
      this.metadata = metadata;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      return new StringBuilder("UnknownInfo [").append(getPath()).append("] : ").append(metadata.toString()).toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FileInfo asFile() {
      throw new IllegalArgumentException("Unknown is not a file: " + getPath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FolderInfo asFolder() {
      throw new IllegalArgumentException("Unknown is not a folder: " + getPath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ItemInfo asItem() {
      throw new IllegalArgumentException("Unknown is not an item: " + getPath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isFolder() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isFile() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isItem() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isDeleted() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getTitle() {
      return metadata.getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getPath() {
      return metadata.getPathLower();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getPathDisplay() {
      return metadata.getPathDisplay();
    }
  }

  /**
   * Dropbox item helper to extract info from its file/folder metadata object.
   */
  protected abstract class ItemInfo extends MetadataInfo {

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isItem() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ItemInfo asItem() {
      return this;
    }

    /**
     * Gets the id.
     *
     * @return the id
     */
    protected abstract String getId();

  }

  /**
   * The Class FileInfo.
   */
  protected class FileInfo extends ItemInfo {

    final FileMetadata file;

    FileInfo(FileMetadata file) {
      this.file = file;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      return new StringBuilder("FileInfo [").append(getPath()).append("] ID: ").append(file.getId()).toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isFolder() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isFile() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isDeleted() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FileInfo asFile() {
      return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FolderInfo asFolder() {
      throw new IllegalArgumentException("File is not a folder: " + getPath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getId() {
      return file.getId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getTitle() {
      return file.getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getPath() {
      return file.getPathLower();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getPathDisplay() {
      return file.getPathDisplay();
    }

    /**
     * Gets the file rev.
     *
     * @return the rev
     */
    protected String getRev() {
      return file.getRev();
    }

    /**
     * Gets the size.
     *
     * @return the size
     */
    protected long getSize() {
      return file.getSize();
    }

    /**
     * Gets the client modified.
     *
     * @return the client modified
     */
    protected Date getClientModified() {
      return file.getClientModified();
    }

    /**
     * Gets the server modified.
     *
     * @return the server modified
     */
    protected Date getServerModified() {
      return file.getServerModified();
    }
  }

  /**
   * The Class FolderInfo.
   */
  protected class FolderInfo extends ItemInfo {

    final FolderMetadata folder;

    FolderInfo(FolderMetadata folder) {
      this.folder = folder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      return new StringBuilder("FolderInfo [").append(getPath()).append("] ID: ").append(folder.getId()).toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isFolder() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isFile() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isDeleted() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FileInfo asFile() {
      throw new IllegalArgumentException("Folder is not a file: " + getPath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FolderInfo asFolder() {
      return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getId() {
      return folder.getId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getTitle() {
      return folder.getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getPath() {
      return folder.getPathLower();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getPathDisplay() {
      return folder.getPathDisplay();
    }
  }

  /**
   * The Class DeletedInfo.
   */
  protected class DeletedInfo extends MetadataInfo {

    final DeletedMetadata deleted;

    DeletedInfo(DeletedMetadata deleted) {
      this.deleted = deleted;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      return new StringBuilder("DeletedInfo [").append(getPath()).append("]").toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isFolder() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isFile() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isItem() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isDeleted() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FileInfo asFile() {
      throw new IllegalArgumentException("Deleted is not a file: " + getPath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FolderInfo asFolder() {
      throw new IllegalArgumentException("Deleted is not a folder: " + getPath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ItemInfo asItem() {
      throw new IllegalArgumentException("Deleted is not an item: " + getPath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getTitle() {
      return deleted.getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getPath() {
      return deleted.getPathLower();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getPathDisplay() {
      return deleted.getPathDisplay();
    }
  }

  /**
   * The Class JCRLocalDropboxFile.
   */
  @Deprecated // lazy-loading has no sense as we return CloudFile from connect/sync command after env/session
              // closing and so delayed JCR read will fail.
  public class JCRLocalDropboxFile extends JCRLocalCloudFile {

    protected final ItemInfo                      file;

    protected String                              link, previewLink, typeMode, author, lastUser;

    protected transient AtomicReference<Calendar> createdDate, modifiedDate;

    // XXX we need these fields declared in the class to let eXo WS JSON serializer work correct and don't
    // invoke/add such related methods.
    // FYI transient fields will not appear in serialized forms like JSON object on client side

    /** The node. */
    @SuppressWarnings("unused")
    private transient Node                        node;

    /** The changed. */
    @SuppressWarnings("unused")
    private transient boolean                     changed;

    /**
     * Instantiates a new local cloud file for Dropbox <b>folder</b>.
     *
     * @param folder the file
     * @param path the path
     * @param id the id
     * @param title the title
     * @param link the link
     * @param type the type
     * @param lastUser the last user
     * @param author the author
     * @param createdDate the created date
     * @param modifiedDate the modified date
     * @param node the node
     * @param changed the changed
     */
    JCRLocalDropboxFile(ItemInfo folder,
                        String path,
                        String id,
                        String title,
                        String link,
                        String type,
                        String lastUser,
                        String author,
                        Calendar createdDate,
                        Calendar modifiedDate,
                        Node node,
                        boolean changed) {
      super(path, id, title, link, FOLDER_TYPE, lastUser, author, createdDate, modifiedDate, node, changed);
      this.file = folder;
      this.link = link;
      this.author = author;
      this.lastUser = lastUser;
      if (createdDate != null) {
        this.createdDate = new AtomicReference<>(createdDate);
      }
      if (modifiedDate != null) {
        this.modifiedDate = new AtomicReference<>(modifiedDate);
      }
    }

    /**
     * Instantiates a new local cloud file for Dropbox <b>file</b>.
     *
     * @param file the file
     * @param path the path
     * @param id the id
     * @param title the title
     * @param link the link
     * @param type the type
     * @param lastUser the last user
     * @param author the author
     * @param createdDate the created date
     * @param modifiedDate the modified date
     * @param size the size
     * @param node the node
     * @param changed the changed
     */
    JCRLocalDropboxFile(ItemInfo file,
                        String path,
                        String id,
                        String title,
                        String link,
                        String type,
                        String lastUser,
                        String author,
                        Calendar createdDate,
                        Calendar modifiedDate,
                        long size,
                        Node node,
                        boolean changed) {
      super(path,
            id,
            title,
            link,
            null, // previewLink
            null, // thumbnailLink
            type,
            null, // typeMode
            lastUser,
            author,
            createdDate,
            modifiedDate,
            size,
            node,
            changed);
      this.file = file;
      this.link = link;
      this.author = author;
      this.lastUser = lastUser;
      if (createdDate != null) {
        this.createdDate = new AtomicReference<>(createdDate);
      }
      if (modifiedDate != null) {
        this.modifiedDate = new AtomicReference<>(modifiedDate);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPreviewLink() {
      if (previewLink == null) {
        try {
          previewLink = previewLink(null, getNode());
        } catch (RepositoryException e) {
          LOG.error("Error reading file preview link: " + getPath(), e);
          previewLink = DUMMY_DATA;
        }
      }
      return DUMMY_DATA == previewLink ? null : previewLink;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTypeMode() {
      if (typeMode == null && !isFolder()) {
        typeMode = mimeTypes.getMimeTypeMode(getType(), getTitle());
      }
      return typeMode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Calendar getCreatedDate() {
      if (createdDate == null) {
        try {
          createdDate = new AtomicReference<>(fileAPI.getCreated(getNode()));
        } catch (RepositoryException e) {
          LOG.error("Error reading file created date: " + getPath(), e);
          createdDate = new AtomicReference<>();
        }
      }
      return createdDate.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Calendar getModifiedDate() {
      if (modifiedDate == null) {
        try {
          modifiedDate = new AtomicReference<>(fileAPI.getModified(getNode()));
        } catch (RepositoryException e) {
          LOG.error("Error reading file modified date: " + getPath(), e);
          modifiedDate = new AtomicReference<>();
        }
      }
      return modifiedDate.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAuthor() {
      if (author == null) {
        try {
          author = fileAPI.getAuthor(getNode());
        } catch (RepositoryException e) {
          LOG.error("Error reading file author: " + getPath(), e);
          author = DUMMY_DATA;
        }
      }
      return DUMMY_DATA == author ? null : author;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLastUser() {
      if (lastUser == null) {
        try {
          lastUser = fileAPI.getLastUser(getNode());
        } catch (RepositoryException e) {
          LOG.error("Error reading file's last modified user: " + getPath(), e);
          lastUser = DUMMY_DATA;
        }
      }
      return DUMMY_DATA == lastUser ? null : lastUser;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLink() {
      if (link == null) {
        if (isFolder()) {
          link = getUser().api().getUserFolderLink(file.getPathDisplay());
        } else {
          link = getUser().api().getUserFileLink(file.getParentPathDisplay(), file.getTitle());
        }
      }
      return link;
    }
  }

  /**
   * The Class DropboxDrive.
   */
  protected class DropboxDrive {

    protected class LocalItem {

      private final String       dbxPath;

      private final Node         node;

      private final Node         parent;

      private final List<String> relPath;

      private Node               updated;

      /**
       * Instantiates a new item location.
       *
       * @param dbxPath the dbxPath
       * @param node the node
       * @param parent the parent
       * @param relPath the rel dbxPath
       */
      protected LocalItem(/* ItemInfo item, */ String dbxPath, Node node, Node parent, List<String> relPath) {
        if (dbxPath == null) {
          throw new IllegalArgumentException("Dropbox path (dbxPath) required");
        }
        this.dbxPath = dbxPath;
        if (node == null && parent == null) {
          throw new IllegalArgumentException("Node or parent required");
        }
        this.node = node;
        this.parent = parent;
        if (node == null && parent != null && (relPath == null || relPath.isEmpty())) {
          throw new IllegalArgumentException("Rellative part of path (relPath list) required");
        }
        this.relPath = relPath != null ? Collections.unmodifiableList(relPath) : null;
      }

      /**
       * Tells if node of this item exists locally.
       *
       * @return true, if item exists locally, false otherwise
       */
      public boolean exists() {
        return getNode() != null;
      }

      /**
       * Gets the local node of this item. If local item doesn't exist, then this method returns
       * <code>null</code> and it's possible to call {@link #fetch(Changes)} to fetch the item state from
       * cloud side or apply existing state from metadata in {@link #update(ItemInfo, Changes)}.
       *
       * @return the node, can be <code>null</code>
       */
      public Node getNode() {
        return updated != null ? updated : node;
      }

      /**
       * Removes the item node and return its path in local storage (JCR) or <code>null</code> if nothing
       * found.
       *
       * @return the string or <code>null</code> if nothing removed
       * @throws RepositoryException the repository exception
       */
      public String removeNode() throws RepositoryException {
        return removeNode(getNode());
      }

      private String removeNode(Node node) throws RepositoryException {
        if (node != null) {
          // remove file links outside the drive, then the node itself
          String jcrPath = node.getPath();
          removePaths(jcrPath);
          JCRLocalDropboxDrive.this.removeNode(node);
          return jcrPath;
        }
        return null;
      }

      /**
       * Update this local item with remote item state.
       *
       * @param item the item
       * @param changes the changes
       * @return the node
       * @throws RepositoryException the repository exception
       * @throws CloudDriveException the cloud drive exception
       */
      public Node update(ItemInfo item, Changes changes) throws RepositoryException, CloudDriveException {
        String itemPath = item.getPath();
        if (itemPath.equals(dbxPath)) {
          JCRLocalCloudFile local = updateItem(api, item, parent, getNode());
          changes.apply(local);
          updated = local.getNode();
          pathNodes.put(itemPath, updated);
          return updated;
        } else {
          throw new IllegalArgumentException("Item path doesn't match the local: " + itemPath + " vs " + dbxPath);
        }
      }

      /**
       * Move this local item to a location reflecting given remote item and update its state.
       *
       * @param toItem the to item
       * @param changes the changes
       * @return the node
       * @throws RepositoryException the repository exception
       * @throws CloudDriveException the cloud drive exception
       */
      public Node move(ItemInfo toItem, Changes changes) throws RepositoryException, CloudDriveException {
        String toPath = toItem.getPath();
        Node fromNode = getNode();
        if (fromNode != null) {
          JCRLocalCloudFile local;
          // check if Dropbox path doesn't match for src and dest locally
          if (toPath.equals(dbxPath)) {
            // Local item node already at the right path in JCR - update it instead of moving
            local = updateItem(api, toItem, parent, fromNode); // pass existing src node
          } else {
            // Check if dest place free or it's not the same node already there (possible due to name
            // normalization - it can remove some characters, e.g. title 'file (5).jpg' will be node name
            // 'file 5.jpg' what is the same as for title 'file 5.jpg').
            LocalItem toLocalItem = toItem.localItem != null ? toItem.localItem : getItem(toPath);
            if (toLocalItem.exists() && hasSameId(toLocalItem.getNode(), toItem)) {
              // Indeed, dest item node already on its place in JCR - update it instead of moving
              local = updateItem(api, toItem, parent, toLocalItem.getNode()); // pass existing node
            } else {
              // Check for a weird case when source not yet a cloud file but same as the destination
              toLocalItem.removeNode(); // It should not exist here otherwise
              try {
                fromNode.refresh(true);
              } catch (InvalidItemStateException e) {
                fromNode = getItem(dbxPath).getNode();
                if (fromNode == null) {
                  throw new LocalFileNotFoundException("Cannot move not cloud file: " + dbxPath, e);
                }
              }
              // We move item node to reflect its JCR path the Dropbox path
              LocalItem toParent = getItem(toItem.getParentPath());
              Node parent = toParent.getNode();
              if (parent == null) {
                // This should not happen, but if it does we fetch it from the cloud side
                LOG.warn("Move's destination parent not found and will be fetched from cloud side: " + toItem.getParentPath());
                parent = toParent.fetch(changes);
              }
              String srcJcrPath = fromNode.getPath();
              // destAbsPath it's path in JCR, thus should be with lower-case dest item name
              String destJcrPath = api.filePath(parent.getPath(), nodeName(toItem.getName()));
              fromNode.getSession().move(srcJcrPath, destJcrPath);
              local = updateItem(api, toItem, parent, null); // will read the node inside
              changes.removed(srcJcrPath); // track source removal to inform the client to refresh the view
            }
          }
          updated = local.getNode();
          removePaths(dbxPath);
          pathNodes.put(toPath, updated);
          changes.apply(local);
        } else {
          throw new IllegalArgumentException("Cannot move an item not found locally: " + dbxPath + " to " + toPath);
        }
        return updated;
      }

      /**
       * Fetch the item state from remote side. Method returns a node of the local cloud item at the path
       * or <code>null</code> if node not found remotely or its dbxPath was changed.
       *
       * @param changes the changes command related to the operation, not <code>null</code>
       * @return the {@link Node} instance of local cloud file or <code>null</code> if no node updated.
       * @throws RepositoryException the repository exception
       * @throws CloudDriveException the cloud drive exception
       */
      public Node fetch(Changes changes) throws RepositoryException, CloudDriveException {
        if (exists()) {
          return getNode();
        }
        Node node = null;
        Node theParent = parent; // starting from existing local parent
        StringBuilder mapPath = new StringBuilder(getDropboxPath(theParent)); // path in Dropbox
        for (Iterator<String> eiter = relPath.iterator(); eiter.hasNext();) {
          String name = eiter.next();
          mapPath.append('/').append(name);
          String itemPath = mapPath.toString();
          node = pathNodes.get(itemPath);
          MetadataInfo item = itemInfo(api.get(itemPath, true));
          if (item.isItem()) {
            // Here we have a situation where existing hierarchy may be build from other (but same
            // name) items and so, by deleting local item (w/ different ID), we'll lose its shared links.
            // But as it's an another file or folder (by ID), it looks that it should be shared by an user
            // again. This seems will happen for restored via API items (they may change ID for folders on the
            // dbxPath).
            JCRLocalCloudFile local = updateItem(api, item, theParent, node);
            changes.apply(local);
            node = local.getNode();
            pathNodes.put(itemPath, node);
            if (item.isFolder()) {
              if (eiter.hasNext() && node.isNew()) {
                // if it's intermediary ancestor on 'not found' path and not found locally, we create it
                // changes.apply(updateItemV2(api, item, theParent, node));
                // fetch whole sub-tree and should finish here
                fetchSubtree(api, item.asFolder().getId(), theParent, null, changes);
                // so we must have the item node at the given path already
                node = getItem(dbxPath).getNode();
                if (node == null) {
                  // This item has no node finally: it could be removed remotely
                  removePaths(dbxPath);
                  node = null;
                }
                break;
              } else {
                theParent = node; // parent for next item in the relPath
              }
            } else {
              if (eiter.hasNext()) {
                // it's not last element in 'not found' subtree path, obviously this item should be a
                // folder, but we have a file from remote side. Thus we stop the loop and tell target node
                // doesn't exist (return null).
                removePaths(itemPath);
                node = null;
                break;
              }
            }
          } else {
            // item should not exist locally
            if (node == null) {
              node = readNode(theParent, name, null);
            }
            if (node != null) {
              // changes.undo(readFile(node)); // TODO need this?
              removeNode(node);
              removePaths(itemPath);
              node = null;
              break;
            }
          }
        }
        return updated = node;
      }
    }

    /**
     * Cloud drive API.
     */
    protected final DropboxAPI        api;

    /** The root node of the tree. */
    protected final Node              rootNode;

    /**
     * Nodes read/created by Dropbox path in this sync.
     */
    protected final Map<String, Node> pathNodes = new HashMap<String, Node>();

    /**
     * Instantiates a new drive tree.
     *
     * @param api the api
     * @param rootNode the root node
     */
    protected DropboxDrive(DropboxAPI api, Node rootNode) {
      this.api = api;
      this.rootNode = rootNode;
      this.pathNodes.put(DropboxAPI.ROOT_PATH_V2, rootNode);
    }

    /**
     * Close the tree and cleanup its temporal resources.
     */
    protected void close() {
      pathNodes.clear();
    }

    void removePaths(String dbxPath) throws RepositoryException {
      for (Iterator<Map.Entry<String, Node>> pniter = pathNodes.entrySet().iterator(); pniter.hasNext();) {
        Map.Entry<String, Node> pne = pniter.next();
        if (pne.getValue().getPath().startsWith(dbxPath)) {
          removeLinks(pne.getValue()); // explicitly remove file links outside the drive
          pniter.remove();
        }
      }
    }

    /**
     * Construct a Dropbox path of an item from given JCR path.
     *
     * @param jcrPath the JCR path
     * @return the remote path
     * @throws RepositoryException the repository exception
     */
    public String getRemotePath(String jcrPath) throws RepositoryException {
      String rootPath = rootNode.getPath();
      if (jcrPath.startsWith(rootPath)) {
        return jcrPath.substring(rootPath.length());
      }
      return null;
    }

    /**
     * Construct a JCR path of an item from given Dropbox path.
     *
     * @param dbxPath the dbx path
     * @return the local path
     * @throws RepositoryException the repository exception
     */
    public String getLocalPath(String dbxPath) throws RepositoryException {
      StringBuilder jcrPath = new StringBuilder(rootNode.getPath());
      String[] pathElems = cleanPath(dbxPath).split("/");
      for (String e : pathElems) {
        jcrPath.append('/').append(nodeName(e));
      }
      return jcrPath.append(dbxPath).toString();
    }

    /**
     * Find local item by its path in the storage (JCR). If given path outside the drive root node then
     * <code>null</code> will be returned.
     *
     * @param jcrPath the JCR path
     * @return the local item or <code>null</code> of path outside the drive root node
     * @throws RepositoryException the repository exception
     */
    public LocalItem findByLocalPath(String jcrPath) throws RepositoryException {
      String dbxPath = getRemotePath(jcrPath);
      if (dbxPath != null) {
        return getItem(dbxPath);
      }
      return null;
    }

    /**
     * Gets the local item.
     *
     * @param dbxPath the Dropbox path
     * @return the local item of type {@link LocalItem}, never <code>null</code>
     * @throws RepositoryException the repository exception
     */
    public LocalItem getItem(String dbxPath) throws RepositoryException {
      // FYI Drive root nodes already in the map
      Node node = pathNodes.get(dbxPath);
      if (node == null) {
        return readItem(dbxPath);
      } else {
        return new LocalItem(dbxPath, node, null, null);
      }
    }

    /**
     * Read item from local storage.
     *
     * @param dbxPath the Dropbox path
     * @return the node location
     * @throws RepositoryException the repository exception
     */
    protected LocalItem readItem(String dbxPath) throws RepositoryException {
      String relPath = cleanPath(dbxPath);
      // we need relative path in JCR from the root node
      Node node = null;
      Node parent = rootNode;
      StringBuilder mapPath = new StringBuilder(); // path in Dropbox
      String itemPath = null;
      List<String> relPathElems = new ArrayList<>(Arrays.asList(relPath.split("/")));
      for (Iterator<String> eiter = relPathElems.iterator(); eiter.hasNext();) {
        String name = eiter.next();
        mapPath.append('/').append(name);
        itemPath = mapPath.toString();
        node = pathNodes.get(itemPath);
        if (node == null) {
          node = readNode(parent, name, null);
          if (node != null) {
            pathNodes.put(itemPath, node);
          } else {
            // This node not found
            break;
          }
        }
        if (eiter.hasNext()) {
          if (fileAPI.isFolder(node)) {
            parent = node;
          } else {
            // if we have more elements in the dbxPath, but current one isn't a folder - it's subtree
            // inconsistency with a remote state.
            // we consider this as 'not found' item with existing parent,
            // later, if location's create() will be called, this node will updated to actual state
            node = null;
            // we break the loop here as no next parent exists
            break;
          }
        }
        eiter.remove();
      }
      if (node != null) {
        return new LocalItem(dbxPath, node, null, null);
      } else if (parent != null) {
        // item or part of its subtree not found
        return new LocalItem(dbxPath, null, parent, Collections.unmodifiableList(relPathElems));
      } else {
        // whole subtree not found locally
        return new LocalItem(dbxPath, null, rootNode, Collections.unmodifiableList(relPathElems));
      }
    }

    /**
     * Find node by Dropbox dbxPath property.
     *
     * @param dbxPath the Dropbox dbxPath
     * @return the node
     * @throws RepositoryException the repository exception
     */
    @Deprecated // Not used
    protected Node findDropboxNode(String dbxPath) throws RepositoryException {
      if (DropboxAPI.ROOT_PATH_V2.equals(dbxPath)) {
        return rootNode;
      } else {
        QueryManager qm = rootNode.getSession().getWorkspace().getQueryManager();
        Query q = qm.createQuery("SELECT * FROM " + ECD_CLOUDFILE + " WHERE dropbox:path='" + dbxPath + "' AND jcr:dbxPath LIKE '"
            + rootNode.getPath() + "/%'", Query.SQL);
        QueryResult qr = q.execute();
        NodeIterator nodes = qr.getNodes();
        if (nodes.hasNext()) {
          return nodes.nextNode();
        }
      }
      return null;
    }

    /**
     * Restore given node at a Dropbox path saved in it or remove the node if it's a connected cloud file.
     *
     * @param node the node
     * @return the node or <code>null</code> if node cannot be found or was removed
     * @throws RepositoryException the repository exception
     */
    public Node restore(Node node) throws RepositoryException {
      String savedDbxPath = getDropboxPath(node);
      String assumedDbxPath = getRemotePath(node.getPath());
      try {
        if (!savedDbxPath.equals(assumedDbxPath)) {
          // move the node to saved path
          rootNode.getSession().move(node.getPath(), getLocalPath(savedDbxPath));
        } // otherwise node already match the path
        return node;
      } catch (PathNotFoundException e) {
        // otherwise, it's not connected cloud file (yet?) - remove it
        node.remove();
      }
      return null;
    }

  }

  /**
   * Dropbox drive state. See {@link #getState()}.
   */
  protected DropboxState state;

  /**
   * Instantiates a new JCR local dropbox drive.
   *
   * @param user the user
   * @param driveNode the drive node
   * @param sessionProviders the session providers
   * @param finder the finder
   * @param mimeTypes the mime types
   * @throws CloudDriveException the cloud drive exception
   * @throws RepositoryException the repository exception
   */
  protected JCRLocalDropboxDrive(DropboxUser user,
                                 Node driveNode,
                                 SessionProviderService sessionProviders,
                                 NodeFinder finder,
                                 ExtendedMimeTypeResolver mimeTypes)
      throws CloudDriveException, RepositoryException {
    super(user, driveNode, sessionProviders, finder, mimeTypes);
    getUser().api().getToken().addListener(this);
  }

  /**
   * Instantiates a new JCR local dropbox drive.
   *
   * @param apiFactory the API factory
   * @param provider the provider
   * @param driveNode the drive node
   * @param sessionProviders the session providers
   * @param finder the finder
   * @param mimeTypes the mime types
   * @throws RepositoryException the repository exception
   * @throws CloudDriveException the cloud drive exception
   */
  protected JCRLocalDropboxDrive(API apiFactory,
                                 DropboxProvider provider,
                                 Node driveNode,
                                 SessionProviderService sessionProviders,
                                 NodeFinder finder,
                                 ExtendedMimeTypeResolver mimeTypes)
      throws RepositoryException, CloudDriveException {
    super(loadUser(apiFactory, provider, driveNode), driveNode, sessionProviders, finder, mimeTypes);
    getUser().api().getToken().addListener(this);

    try {
      String cursor = driveNode.getProperty("dropbox:cursor").getString();
      updateState(cursor);
    } catch (PathNotFoundException e) {
      LOG.warn("Drive node exists but delta cursor not found for " + title() + ": " + e.getMessage());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void initDrive(Node driveNode) throws CloudDriveException, RepositoryException {
    super.initDrive(driveNode);
    driveNode.setProperty("ecd:id", DropboxAPI.ROOT_PATH_V2);
    driveNode.setProperty("ecd:url", DropboxAPI.ROOT_URL);
    initDropboxPath(driveNode, DropboxAPI.ROOT_PATH_V2);
  }

  /**
   * Update state.
   *
   * @param cursor the cursor
   */
  protected void updateState(String cursor) {
    this.state = new DropboxState(cursor);
  }

  /**
   * Load user from the drive Node.
   *
   * @param apiFactory {@link API} API factory
   * @param provider {@link DropboxProvider}
   * @param driveNode {@link Node} root of the drive
   * @return {@link DropboxUser}
   * @throws RepositoryException the repository exception
   * @throws DropboxException the dropbox exception
   * @throws CloudDriveException the cloud drive exception
   */
  protected static DropboxUser loadUser(API apiFactory, DropboxProvider provider, Node driveNode) throws RepositoryException,
                                                                                                  DropboxException,
                                                                                                  CloudDriveException {
    String username = driveNode.getProperty("ecd:cloudUserName").getString();
    String email = driveNode.getProperty("ecd:userEmail").getString();
    String userId = driveNode.getProperty("ecd:cloudUserId").getString();

    String accessToken = driveNode.getProperty("dropbox:oauth2AccessToken").getString();
    DropboxAPI driveAPI = apiFactory.load(accessToken).build();

    return new DropboxUser(userId, username, email, provider, driveAPI);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onUserTokenRefresh(UserToken token) throws CloudDriveException {
    try {
      jcrListener.disable();
      Node driveNode = rootNode();
      try {
        driveNode.setProperty("dropbox:oauth2AccessToken", token.getAccessToken());
        driveNode.save();
      } catch (RepositoryException e) {
        rollback(driveNode);
        throw new CloudDriveException("Error updating access key: " + e.getMessage(), e);
      }
    } catch (DriveRemovedException e) {
      throw new CloudDriveException("Error openning drive node: " + e.getMessage(), e);
    } catch (RepositoryException e) {
      throw new CloudDriveException("Error reading drive node: " + e.getMessage(), e);
    } finally {
      jcrListener.enable();
    }
  }

  /**
   * {@inheritDoc}
   * 
   */
  @Override
  public void onUserTokenRemove() throws CloudDriveException {
    try {
      jcrListener.disable();
      Node driveNode = rootNode();
      try {
        if (driveNode.hasProperty("dropbox:oauth2AccessToken")) {
          driveNode.getProperty("dropbox:oauth2AccessToken").remove();
        }

        driveNode.save();
      } catch (RepositoryException e) {
        rollback(driveNode);
        throw new CloudDriveException("Error removing access key: " + e.getMessage(), e);
      }
    } catch (DriveRemovedException e) {
      throw new CloudDriveException("Error openning drive node: " + e.getMessage(), e);
    } catch (RepositoryException e) {
      throw new CloudDriveException("Error reading drive node: " + e.getMessage(), e);
    } finally {
      jcrListener.enable();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ContentReader getFileContent(String idPath) throws RepositoryException, CloudDriveException {
    DropboxAPI api = getUser().api();
    DbxDownloader<FileMetadata> downloader = api.getContent(idPath);
    MetadataInfo item = itemInfo(downloader.getResult());
    if (item.isFile()) {
      FileInfo file = item.asFile();
      String type = findMimetype(file.getTitle());
      String typeMode = mimeTypes.getMimeTypeMode(type, file.getTitle());
      return new CloudFileContent(downloader.getInputStream(), type, typeMode, file.getSize());
    } else {
      LOG.warn("File content request returned non file item: " + item);
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FilesState getState() throws DriveRemovedException, RefreshAccessException, CloudProviderException, RepositoryException {
    return state;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSharingSupported() {
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void shareFile(Node fileNode, String... users) throws RepositoryException, CloudDriveException {
    // Standard Dropbox account can share file publicly only. We cannot set per-user permissions here.

    // TODO Implement Team sharing according Dropbox API https://www.dropbox.com/developers/core/docs#shares
    // Dropbox for Business users can set restrictions on shared links; the visibility field indicates
    // what (if any) restrictions are set on this particular link. Possible values include: "PUBLIC" (anyone
    // can view), "TEAM_ONLY" (only the owner's team can view), "PASSWORD" (a password is required),
    // "TEAM_AND_PASSWORD" (a combination of "TEAM_ONLY" and "PASSWORD" restrictions), or "SHARED_FOLDER_ONLY"
    // (only members of the enclosing shared folder can view). Note that other values may be added at any
    // time.

    createSharedLink(fileNode);
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
    return new EventsSync();
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
      return rootNode().getProperty("dropbox:changePosition").getLong();
    } catch (PathNotFoundException e) {
      throw new CloudDriveException("Change id not found for the drive " + title(), e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void saveChangeId(Long id) throws CloudDriveException, RepositoryException {
    Node driveNode = rootNode();
    // will be saved in a single save of the drive command (sync)
    driveNode.setProperty("dropbox:changePosition", id);
    driveNode.setProperty("dropbox:changeDate", Calendar.getInstance());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DropboxUser getUser() {
    return (DropboxUser) user;
  }

  // public

  /**
   * {@inheritDoc}
   */
  @Override
  protected void refreshAccess() throws CloudDriveException {
    // Dropbox token renewal will be done on RefreshAccessException raised from the DropboxAPI methods
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void updateAccess(CloudUser newUser) throws CloudDriveException, RepositoryException {
    getUser().api().updateToken(((DropboxUser) newUser).api().getToken());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void fixNameConflict(Node file) throws RepositoryException {
    super.fixNameConflict(file);

    // ensure we follow the rule or lower-case path in JCR for lookup in DropboxDrive
    normalizeName(file);
  }

  /**
   * Ensure the Dropbox file node has name in lower-case. If name requires change it will be renamed in the
   * current session.<br>
   * This step required for {@link DropboxDrive} work.<br>
   * NOTE: this method does't check if it is a cloud file and doesn't respect JCR namespaces and will check
   * against the whole name of the file.
   *
   * @param fileNode {@link Node}
   * @throws RepositoryException the repository exception
   */
  protected void normalizeName(Node fileNode) throws RepositoryException {
    String jcrName = fileNode.getName();
    String lcName = nodeName(getUser().api().lowerCase(fileAPI.getTitle(fileNode)));
    if (!lcName.equals(jcrName)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Normalizing node name: " + jcrName + " -> " + lcName);
      }
      fileNode.getSession().move(fileNode.getPath(), getUser().api().filePath(fileNode.getParent().getPath(), lcName));
    }
  }

  /**
   * Initialize Dropbox file.
   *
   * @param localNode {@link Node}
   * @param rev the rev
   * @param size the size
   * @throws RepositoryException the repository exception
   * @throws DropboxException the dropbox exception
   */
  protected void initDropboxFile(Node localNode, String rev, Long size) throws RepositoryException, DropboxException {
    // File revision for synchronization
    localNode.setProperty("dropbox:rev", rev);

    // File size
    localNode.setProperty("ecd:size", size);
  }

  /**
   * Initialize Dropbox folder.
   *
   * @param localNode {@link Node}
   * @param cursor String a cursor of last synchronization, if <code>null</code> it will be ignored
   * @throws RepositoryException the repository exception
   * @throws DropboxException the dropbox exception
   */
  protected void initDropboxFolder(Node localNode, String cursor) throws RepositoryException, DropboxException {
    if (cursor != null) {
      // localNode.setProperty("dropbox:hash", deltaHash);
      if (localNode.hasProperty("dropbox:hash")) {
        // Folder has no hash in SDK v2, hash actual only for files
        localNode.getProperty("dropbox:hash").remove();
      }
    }
  }

  /**
   * Init Dropbox dbxPath of local item.
   *
   * @param localNode the local node
   * @param path the path
   * @throws RepositoryException the repository exception
   */
  protected void initDropboxPath(Node localNode, String path) throws RepositoryException {
    localNode.setProperty("dropbox:path", path);
  }

  /**
   * Get Dropbox path saved in local item. If this path not found in the item, a <code>null</code> will
   * be returned.
   *
   * @param localNode the local node
   * @return the Dropbox path or <code>null</code>
   * @throws RepositoryException the repository exception
   */
  protected String getDropboxPath(Node localNode) throws RepositoryException {
    return localNode.getProperty("dropbox:path").getString();
  }

  /**
   * Update, create or delete a local node of Cloud File. If the node is <code>null</code> then it will be
   * open on the given parent and created if not already exists. If item metadata is not file or folder
   * type, then CloudDriveException will be thrown.
   *
   * @param api {@link DropboxAPI}
   * @param update the update
   * @param parent {@link Node}, can be <code>null</code> if node given
   * @param node {@link Node}, can be <code>null</code> if parent given
   * @return {@link JCRLocalCloudFile}
   * @throws RepositoryException for storage errors
   * @throws CloudDriveException for drive or format errors, or if item metadata not of existing file or
   *           folder
   */
  protected JCRLocalCloudFile updateItem(DropboxAPI api, MetadataInfo update, Node parent, Node node) throws RepositoryException,
                                                                                                      CloudDriveException {
    if (update.isItem()) {
      ItemInfo item = update.asItem();

      String id = item.getId();
      String title = item.getTitle(); // title as natural item name in Dropbox (case preserved)

      Calendar created, modified;
      String createdBy, modifiedBy;

      // TODO get name of modifier from Dropbox metadata
      createdBy = modifiedBy = currentUserName();

      boolean changed;
      String link;
      String type;
      JCRLocalCloudFile localFile;
      // FYI we apply lower-case rule for Dropbox local file names, it will be used for a lookup by
      // DropboxDrive class
      if (item.isFolder()) {
        FolderInfo folder = item.asFolder();
        // read/create local node if not given
        // Dropbox docs say:
        // If the new entry is a folder, check what your local state has at path. If it's a file, replace
        // it with the new entry. If it's a folder, apply the new metadata to the folder, but don't modify
        // the folder's children. If your local state doesn't yet include this dbxPath, create it as a folder.
        if (node == null) {
          if (parent != null) {
            node = readNode(parent, folder.getName(), id);
          } else {
            throw new IllegalArgumentException("Parent or node required");
          }
        }
        if (node == null) {
          node = openFolder(id, folder.getName(), parent);
        } else if (/* hasDifferentId(node, folder) || */ fileAPI.isFile(node) && !fileAPI.isFolder(node)) {
          // FYI ID may change if file (with hierarchy folders) restored
          parent = parent != null ? parent : node.getParent();
          removeNode(node);
          node = openFolder(id, folder.getName(), parent);
        } // else, we already have a right item
        type = FOLDER_TYPE;
        if (node.isNew() || hasLocationChanged(node, folder)) {
          changed = true;
          if (node.isNew()) {
            created = modified = Calendar.getInstance(); // XXX Dropbox SDK doesn't have folder's dates
          } else {
            modified = Calendar.getInstance();
            try {
              // if 'created' date exists - don't change it, otherwise the same as modified
              fileAPI.getCreated(node);
              created = null;
            } catch (PathNotFoundException e) {
              created = modified;
            }
          }
          link = api.getUserFolderLink(folder.getPathDisplay());
          initFolder(node, id, title, type, link, createdBy, modifiedBy, created, modified);
          initDropboxPath(node, folder.getPath());
        } else {
          // only "changed" set here, others will read on-demand by the file instance
          changed = false;
          created = modified = null; // will not change stored locally
          link = null;
        }
        localFile = new JCRLocalCloudFile(node.getPath(), id, title, link, type, modifiedBy, createdBy, created, modified, node, changed);
      } else {
        FileInfo file = item.asFile();
        // read/create local node if not given
        // Dropbox docs say:
        // If the new entry is a file, replace whatever your local state has at path with the new entry.
        if (node == null) {
          if (parent != null) {
            node = readNode(parent, file.getName(), id);
          } else {
            throw new IllegalArgumentException("Parent or node required");
          }
        }
        if (node == null) {
          node = openFile(id, file.getName(), parent);
        } else if (/* hasDifferentId(node, file) || */ fileAPI.isFolder(node)) {
          // FYI ID may change if file (with hierarchy folders) restored
          parent = parent != null ? parent : node.getParent();
          removeNode(node);
          node = openFile(id, file.getName(), parent);
        } // else, we already have a right item
        if (node.isNew() || hasDifferentRev(node, file) || hasLocationChanged(node, file)) {
          changed = true;
          if (node.isNew()) {
            created = Calendar.getInstance();
            created.setTime(file.getClientModified());
          } else {
            try {
              // if 'created' date exists - don't change it
              fileAPI.getCreated(node);
              created = null; // keep existing
            } catch (PathNotFoundException e) {
              created = Calendar.getInstance();
              created.setTime(file.getClientModified());
            }
          }
          modified = Calendar.getInstance();
          modified.setTime(file.getServerModified());
          type = findMimetype(title);
          link = api.getUserFileLink(file.getParentPathDisplay(), file.getTitle());
          initFile(node,
                   id,
                   title,
                   type,
                   link,
                   null, // see previewLink()
                   null, // thumbnailLink, TODO use eXo Thumbnails services in conjunction with Dropbox thumbs
                   createdBy,
                   modifiedBy,
                   created,
                   modified,
                   file.getSize());
          initDropboxPath(node, file.getPath());
          initDropboxFile(node, file.getRev(), file.getSize());
        } else {
          // only "changed" set here
          changed = false;
          created = modified = null;
          link = null;
          type = null;
        }
        localFile = new JCRLocalCloudFile(node.getPath(),
                                          id,
                                          title,
                                          link,
                                          previewLink(null, node), // previewLink
                                          null, // thumbnailLink
                                          type,
                                          mimeTypes.getMimeTypeMode(type, title), // typeMode
                                          modifiedBy,
                                          createdBy,
                                          created,
                                          modified,
                                          file.getSize(),
                                          node,
                                          changed);
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Updated item: " + localFile.getPath() + " [" + localFile.getId() + ", " + item.getPath() + "] changed: " + localFile.isChanged()
            + " folder: " + localFile.isFolder());
      }
      return localFile;
    } else if (update.isDeleted()) {
      throw new CloudDriveException("Deleted item cannot be updated: " + update);
    } else {
      throw new CloudDriveException("Unexpected item update type: " + update);
    }
  }

  /**
   * Creates the shared link.
   *
   * @param fileNode the file node
   * @return the string
   * @throws RepositoryException the repository exception
   * @throws CloudDriveException the cloud drive exception
   */
  protected String createSharedLink(Node fileNode) throws RepositoryException, CloudDriveException {
    String idPath = fileAPI.getId(fileNode);
    // obtain shared link from Dropbox
    // WARN this call goes under the drive owner credentials on Dropbox
    SharedLinkMetadata metadata = getUser().api().createSharedLink(idPath);
    jcrListener.disable();
    try {
      String link = metadata.getUrl();
      Property dlProp = fileNode.setProperty("dropbox:sharedLink", link);
      long sharedLinkExpires;
      if (metadata.getExpires() != null) {
        sharedLinkExpires = metadata.getExpires().getTime();
      } else {
        sharedLinkExpires = -1; // never expire
      }
      Property dleProp = fileNode.setProperty("dropbox:sharedLinkExpires", sharedLinkExpires);
      if (dlProp.isNew()) {
        if (!fileNode.isNew() && !fileNode.isModified()) { // save only if node was already saved
          fileNode.save();
        } // otherwise, it should be saved where the node added/modified (by the owner)
      } else {
        // save only direct link properties for!
        dlProp.save();
        dleProp.save();
      }
      return link;
    } finally {
      jcrListener.enable();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String link(Node fileNode) throws RepositoryException {
    // check is it a drive owner or does the file already shared to outside the owner's
    // Personal Docs, and if not owner and shared already - we return shared link, otherwise return default
    // (home-based) URL.

    String currentUser = currentUserName();
    boolean isNotOwner;
    try {
      String driveOwner = rootNode().getProperty("ecd:localUserName").getString();
      isNotOwner = !driveOwner.equals(currentUser);
    } catch (DriveRemovedException e) {
      LOG.warn("Cannot read drive owner: " + e.getMessage());
      isNotOwner = false;
    }

    if (isNotOwner) {
      // Here we assume the following:
      // * file or folder was shared to other users by the drive owner
      // * owner of the drive agreed that he/she shares the file to others as Dropbox does for standard
      // account
      // * if owner will decide to remove the link (revoke the sharing), then this link will not be valid and
      // others will not be able to access the file. To share the file again, the owner needs to copy-paste
      // the file to its shared destination again or set such permission in Cloud File Sharing menu.

      // Shared link expires on Dropbox (as for Aug 11, 2015 all expirations at Jan 1 2030), respect this fact
      String sharedLink;
      boolean acquireNew = false;
      try {
        sharedLink = fileNode.getProperty("dropbox:sharedLink").getString();
        long expires = fileNode.getProperty("dropbox:sharedLinkExpires").getLong();
        if (expires == -1 || expires > System.currentTimeMillis()) {
          return sharedLink;
        } else {
          // re-acquire a new shared link (this seems should happen after Jan 1 2030)
          acquireNew = true;
        }
      } catch (PathNotFoundException e) {
        // shared link not saved locally, it may mean several things:
        // * it is a file in shared folder and need acquire a shared link for this file
        Node parent = fileNode.getParent();
        if (fileAPI.isFolder(parent) && parent.hasProperty("dropbox:sharedLink")) {
          // if folder already shared...
          // in case of sub-folder in shared folder, a shared link for the sub-folder will be acquired
          // naturally in result of navigation in Documents explorer (except of explicit path pointing what
          // is not possible for human usecases)
          acquireNew = true;
        }
        // * the file wasn't shared via symlink but access somehow by other user (e.g. by root user, system
        // session) - in this case we don't want do anything and return default link
      }

      if (acquireNew) {
        // get a shared link from Dropbox here
        try {
          Node node;
          if (isNotOwner) {
            // if not owner need use system session to have rights to save the sharedLink in the node
            node = (Node) systemSession().getItem(fileNode.getPath());
          } else {
            node = fileNode;
          }
          return createSharedLink(node);
        } catch (CloudDriveException e) {
          LOG.error("Error creating shared link of Dropbox file " + fileAPI.getId(fileNode), e);
        }
      }
    }
    // by default, we show saved locally link build respectively the Home page of current (owner) user
    return super.link(fileNode);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String previewLink(String type, Node fileNode) throws RepositoryException {
    String id = fileAPI.getId(fileNode);
    // Direct link expires on Dropbox, respect this fact
    String directLink;
    try {
      directLink = fileNode.getProperty("dropbox:directLink").getString();
      long expires = fileNode.getProperty("dropbox:directLinkExpires").getLong();
      if (expires > System.currentTimeMillis()) {
        return directLink;
      }
    } catch (PathNotFoundException e) {
      // well, will try get it from Dropbox
    }
    try {
      directLink = getUser().api().getDirectLink(id);
      jcrListener.disable();
      Node node;
      // if not owner and not new node need use system session to have rights to save the directLink in the
      // node
      String currentUser = currentUserName();
      String driveOwner = rootNode().getProperty("ecd:localUserName").getString();
      if (!driveOwner.equals(currentUser) && !fileNode.isNew()) {
        node = (Node) systemSession().getItem(fileNode.getPath());
      } else {
        node = fileNode;
      }
      // properties should be saved within the drive node (e.g. in sync files command)
      Property dlProp = node.setProperty("dropbox:directLink", directLink);
      Property dleProp = node.setProperty("dropbox:directLinkExpires", System.currentTimeMillis() + DropboxAPI.TEMP_LINK_EXPIRATION);
      if (dlProp.isNew()) {
        if (!node.isNew() && !node.isModified()) { // this should work for only already saved nodes
          node.save();
        } // otherwise, it should be saved where the node added/modified
      } else {
        // save only direct link properties for !
        dlProp.save();
        dleProp.save();
      }
      return directLink;
    } catch (NotAcceptableException e) {
      LOG.warn("Error getting direct link of Dropbox file " + id + ": " + e.getMessage(), e);
    } catch (DriveRemovedException e) {
      LOG.warn("Error getting direct link of Dropbox file " + id + ": " + e.getMessage(), e);
    } catch (DropboxException e) {
      LOG.error("Error getting direct link of Dropbox file " + id, e);
    } catch (RefreshAccessException e) {
      LOG.warn("Cannot getting direct link of Dropbox file " + id + ": authorization required.", e);
    } catch (NotFoundException e) {
      LOG.error("Error getting direct link of Dropbox file " + id, e);
    } catch (RetryLaterException e) {
      LOG.error("Error getting direct link of Dropbox file " + id, e);
    } finally {
      jcrListener.enable();
    }
    // by default, we'll stream the file content via eXo REST service link
    // FIXME in case of non-viewable document this may do bad UX (a download popup will appear)
    return ContentService.contentLink(rootWorkspace, fileNode.getPath(), id);
  }

  /**
   * Find mimetype.
   *
   * @param fileName the file name
   * @return the string
   */
  protected String findMimetype(String fileName) {
    final String defaultType = mimeTypes.getDefaultMimeType();
    String fileType;
    String resolvedType = mimeTypes.getMimeType(fileName);
    if (resolvedType != null && !resolvedType.startsWith(defaultType)) {
      fileType = resolvedType;
    } else {
      fileType = defaultType;
    }
    return fileType;
  }

  /**
   * Fetch subtree (children) of given item from remote side.
   *
   * @param api the api
   * @param itemId the item id
   * @param node the node
   * @param iterators the iterators
   * @param changes the changes
   * @return the list folder
   * @throws RepositoryException the repository exception
   * @throws CloudDriveException the cloud drive exception
   */
  protected ListFolder fetchSubtree(DropboxAPI api,
                                    String itemId,
                                    Node node,
                                    Collection<ChunkIterator<?>> iterators,
                                    Changes changes) throws RepositoryException, CloudDriveException {
    ListFolder ls = api.listFolder(itemId);
    while (ls.hasNext() && !Thread.currentThread().isInterrupted()) {
      MetadataInfo dbxItem = itemInfo(ls.next());
      fetchItem(api, dbxItem, itemId, node, iterators, changes);
    }
    return ls;
  }

  /**
   * Fetch remote item, for a folder do recursive.
   *
   * @param api the api
   * @param item the item metadata
   * @param parentId the parent ID
   * @param parentNode the parent node
   * @param iterators the iterators for progress tracking, can be <code>null</code>
   * @param changes the changes command (e.g. connect, sync or restore), never <code>null</code>
   * @throws RepositoryException the repository exception
   * @throws NotFoundException the not found exception
   * @throws CloudDriveException the cloud drive exception
   */
  protected void fetchItem(DropboxAPI api,
                           MetadataInfo item,
                           String parentId,
                           Node parentNode,
                           Collection<ChunkIterator<?>> iterators,
                           Changes changes) throws RepositoryException, NotFoundException, CloudDriveException {

    if (item.isFolder()) {
      // It's folder
      FolderInfo folder = item.asFolder();
      if (changes.canApply(parentId, folder.getId())) {
        JCRLocalCloudFile localItem = updateItem(api, folder, parentNode, null);
        changes.apply(localItem);

        ListFolder ls;
        try {
          ls = api.listFolder(folder.getId());
          if (iterators != null) {
            iterators.add(ls);
          }
          while (ls.hasNext()) {
            Metadata child = ls.next();
            MetadataInfo childItem = itemInfo(child);
            // go recursive for the folder childs
            fetchItem(api, childItem, localItem.getId(), localItem.getNode(), iterators, changes);
          }
        } catch (NotFoundException e) {
          // may have a place if folder was just removed after the list request (during this fetching)
          removeNode(localItem.getNode());
          changes.undo(localItem);
        }
      }
    } else if (item.isFile()) {
      // It's file
      FileInfo file = item.asFile();
      if (changes.canApply(parentId, file.getId())) {
        JCRLocalCloudFile localItem = updateItem(api, file, parentNode, null);
        changes.apply(localItem);
      }
    } else if (item.isDeleted()) {
      // FYI as for Connect command this should not happen except if deleted during fetching
      Node node = readNode(parentNode, item.getName(), null);
      if (node != null) {
        JCRLocalCloudFile localItem = readFile(node);
        LOG.warn("Fetched item deleted remotelly: " + localItem.getPath() + " >> " + item.getPath());
        removeNode(node);
        changes.undo(localItem);
      } // else, local already not found
    } else {
      LOG.warn("Remote item type unknown: " + item.getPath());
    }
  }

  /**
   * Folder cursor.
   *
   * @param node the node
   * @return the string
   * @throws RepositoryException the repository exception
   */
  protected String folderCursor(Node node) throws RepositoryException {
    String cursor;
    if (fileAPI.isFolder(node)) {
      try {
        cursor = node.getProperty("dropbox:cursor").getString();
      } catch (PathNotFoundException e) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Folder cursor not found in the node: " + node.getPath());
        }
        cursor = null;
      }
    } else {
      cursor = null;
    }
    return cursor;
  }

  // /** TODO
  // * Clean expired moved.
  // */
  // protected void cleanExpiredMoved() {
  // for (Iterator<MovedFile> fiter = moved.values().iterator(); fiter.hasNext();) {
  // MovedFile file = fiter.next();
  // if (file.isOutdated()) {
  // fiter.remove();
  // }
  // }
  // }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String nodeName(String title) {
    // All node names lower-case for Dropbox files to make lookup by Dropbox path possible in DropboxDrive.
    // This done on node creation phase when we use not title but name from Dropbox and it is already
    // lower-case
    // return super.nodeName(getUser().api().lowerCase(title)); // TODO
    return super.nodeName(title);
  }

  /**
   * Metadata info.
   *
   * @param item the item
   * @return the info
   */
  protected MetadataInfo itemInfo(Metadata item) {
    if (FileMetadata.class.isAssignableFrom(item.getClass())) {
      FileMetadata dbxFile = FileMetadata.class.cast(item);
      return new FileInfo(dbxFile);
    } else if (FolderMetadata.class.isAssignableFrom(item.getClass())) {
      FolderMetadata dbxFolder = FolderMetadata.class.cast(item);
      return new FolderInfo(dbxFolder);
    } else if (DeletedMetadata.class.isAssignableFrom(item.getClass())) {
      DeletedMetadata dbxDeleted = DeletedMetadata.class.cast(item);
      return new DeletedInfo(dbxDeleted);
    } else {
      return new UnknownInfo(item);
    }
  }

  /**
   * Checks for location changed.
   *
   * @param node the local item node
   * @param item the remote item
   * @return true, if successful
   * @throws RepositoryException the repository exception
   */
  protected boolean hasLocationChanged(Node node, ItemInfo item) throws RepositoryException {
    try {
      return !getDropboxPath(node).equals(item.getPath()) /* || hasDifferentId(node, item) */;
    } catch (PathNotFoundException e) {
      // if path property not found - it's not yet cloud file or ignored, in this context we assume it's
      // location change, i.e. new file.
      return true;
    }
  }

  /**
   * Checks for same ID of local node and remote item.
   *
   * @param node the node
   * @param item the item
   * @return true, if successful
   * @throws RepositoryException the repository exception
   */
  protected boolean hasSameId(Node node, ItemInfo item) throws RepositoryException {
    try {
      return fileAPI.getId(node).equals(item.getId());
    } catch (PathNotFoundException e) {
      // if ID property not found - it's not yet cloud file or ignored, in this context we assume it's
      // different IDs, i.e. new file.
      return false;
    }
  }

  /**
   * Checks for different rev.
   *
   * @param node the node
   * @param file the file
   * @return true, if successful
   * @throws RepositoryException the repository exception
   */
  protected boolean hasDifferentRev(Node node, FileInfo file) throws RepositoryException {
    try {
      return !node.getProperty("dropbox:rev").getString().equals(file.getRev());
    } catch (PathNotFoundException e) {
      // if rev property not found - it's not yet cloud file or ignored, in this context we assume it's
      // different revisions, i.e. new file.
      return true;
    }
  }

  /**
   * Clean path from leading and ending slashes.
   *
   * @param path the path
   * @return the string
   */
  protected String cleanPath(String path) {
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

}
