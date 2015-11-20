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
package org.exoplatform.clouddrive.dropbox;

import com.dropbox.core.DbxClient.Downloader;
import com.dropbox.core.DbxDelta;
import com.dropbox.core.DbxEntry;
import com.dropbox.core.DbxUrlWithExpiration;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudFile;
import org.exoplatform.clouddrive.CloudFileAPI;
import org.exoplatform.clouddrive.CloudProviderException;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.ConflictException;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.FileRestoreException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.SyncNotSupportedException;
import org.exoplatform.clouddrive.dropbox.DropboxAPI.DeltaChanges;
import org.exoplatform.clouddrive.dropbox.DropboxAPI.FileMetadata;
import org.exoplatform.clouddrive.dropbox.DropboxConnector.API;
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
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.ValueFormatException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.version.VersionException;

/**
 * Local drive for Dropbox provider.<br>
 * 
 */
public class JCRLocalDropboxDrive extends JCRLocalCloudDrive implements UserTokenRefreshListener {

  /**
   * Time to expire for file links obtained from Dropbox without explicitly set expiration time.
   */
  public static final long   DEFAULT_LINK_EXPIRATION_PERIOD = 3 * 60 * 60 * 1000; // 3hrs

  public static final String FOLDER_REV                     = "".intern();

  public static final String FOLDER_TYPE                    = "folder".intern();

  /**
   * Applicable changes of local Drobpox drive.
   */
  protected interface Changes {

    /**
     * Process locally applied file (it can be any extra operations including the gathering of effected
     * files/stats or chunk saving in JRC).
     * 
     * @param changedFile {@link JCRLocalCloudFile} changed file
     * @throws RepositoryException
     * @throws CloudDriveException
     */
    void apply(JCRLocalCloudFile changedFile) throws RepositoryException, CloudDriveException;

    /**
     * Answers if given file ID under its parent (by ID) already applied locally.
     * 
     * @param parentId {@link String}
     * @param fileId {@link String}
     * @return boolean, <code>true</code> if file was already applied, <code>false</code> otherwise.
     * @see
     */
    boolean canApply(String parentId, String fileId);
  }

  /**
   * Connect algorithm for Drobpox drive.
   */
  protected class Connect extends ConnectCommand implements Changes {

    protected final DropboxAPI api;

    protected Connect() throws RepositoryException, DriveRemovedException {
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void fetchFiles() throws CloudDriveException, RepositoryException {
      long changeId = System.currentTimeMillis(); // time of the begin

      // Obtain initial delta cursor before the actual fetch of cloud files,
      // this will provide us a proper cursor to start sync from later.
      String connectCursor = api.getDeltas(null).getCursor();

      fetchSubtree(api, DropboxAPI.ROOT_PATH, driveNode, false, iterators, this);

      // sync stream
      setChangeId(changeId);
      driveNode.setProperty("dropbox:cursor", connectCursor);
      updateState(connectCursor);
    }

    public void apply(JCRLocalCloudFile localFile) throws RepositoryException, CloudDriveException {
      String parentIdPath = fileAPI.getParentId(localFile.getNode());
      addConnected(parentIdPath, localFile);
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
   * {@link SyncCommand} of cloud drive based on all remote files traversing using folder hashes stoed locally
   * on connect or previous full sync.
   * 
   * TODO it could be used when *reset* flag set in delta entry.
   */
  @Deprecated // NOT SURE IT IS REQUIRED
  protected class FullSync extends SyncCommand {

    /**
     * Internal API.
     */
    protected final DropboxAPI api;

    /**
     * Create command for Template synchronization.
     * 
     * @throws RepositoryException
     * @throws DriveRemovedException
     */
    protected FullSync() throws RepositoryException, DriveRemovedException {
      super();
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void syncFiles() throws RepositoryException, CloudDriveException {
      long changeId = System.currentTimeMillis(); // time of the begin

      // real all local nodes of this drive
      readLocalNodes();

      // remember full sync position (same logic as in Connect command)
      String syncCursor = api.getDeltas(null).getCursor();

      // sync with cloud
      Object root = syncChilds(syncCursor, driveNode); // TODO use actual ID
      initCloudItem(driveNode, root); // init parent

      // remove local nodes of files not existing remotely, except of root
      nodes.remove("ROOT_ID"); // TODO use actual ID
      for (Iterator<List<Node>> niter = nodes.values().iterator(); niter.hasNext()
          && !Thread.currentThread().isInterrupted();) {
        List<Node> nls = niter.next();
        niter.remove();
        for (Node n : nls) {
          String npath = n.getPath();
          if (notInRange(npath, getRemoved())) {
            removeLinks(n); // explicitly remove file links outside the drive
            n.remove();
            addRemoved(npath);
          }
        }
      }

      // update sync position
      setChangeId(changeId);
      driveNode.setProperty("dropbox:cursor", syncCursor);
      updateState(syncCursor);
    }

    protected Object syncChilds(String folderId, Node parent) throws RepositoryException, CloudDriveException {
      FileMetadata items = api.getWithChildren(folderId, null);// TODO items can be null
      iterators.add(items);
      while (items.hasNext() && !Thread.currentThread().isInterrupted()) {
        DbxEntry item = items.next();

        DbxFileInfo file = new DbxFileInfo(item.path);
        if (file.isRoot()) {
          // skip root node - this shouldn't happen
          if (LOG.isDebugEnabled()) {
            LOG.debug("Fetched root folder entry - ignore it: " + item.path);
          }
          continue;
        }

        // remove from map of local to mark the item as existing
        List<Node> existing = nodes.remove("TODO item.getId()");

        JCRLocalCloudFile localItem = updateItem(api, file, item, parent, null);
        if (localItem.isChanged()) {
          addChanged(localItem);

          // cleanup of this file located in another place (usecase of rename/move)
          // XXX this also assumes that cloud doesn't support linking of files to other folders
          if (existing != null) {
            for (Iterator<Node> eiter = existing.iterator(); eiter.hasNext();) {
              Node enode = eiter.next();
              String path = localItem.getPath();
              String epath = enode.getPath();
              if (!epath.equals(path) && notInRange(epath, getRemoved())) {
                removeLinks(enode); // explicitly remove file links outside the drive
                enode.remove();
                addRemoved(epath);
                eiter.remove();
              }
            }
          }
        }

        if (localItem.isFolder()) {
          // go recursive to the folder
          syncChilds(localItem.getId(), localItem.getNode());
        }
      }
      return items.target;
    }

    /**
     * Execute full sync from current thread.
     */
    protected void execLocal() throws CloudDriveException, RepositoryException {
      // XXX we need this to be able run it from EventsSync.syncFiles()
      commandEnv.configure(this);

      super.exec();

      // at this point we know all changes already applied - we don't need history anymore in super class
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
    protected void preSaveChunk() throws CloudDriveException, RepositoryException {
      // nothing save for full sync
    }
  }

  protected class MovedFile {

    /**
     * Dropbox path (case preserved) of moved file on its destination (after move completed).
     */
    protected final String path;

    /**
     * Destination node path in JCR.
     */
    protected final String nodePath;

    /**
     * Expiration time.
     */
    protected final long   expirationTime;

    protected MovedFile(String path, String nodePath) {
      super();
      this.path = path;
      this.nodePath = nodePath;
      // XXX 15sec to outdate - it is actually a nasty thing that can make troubles
      this.expirationTime = System.currentTimeMillis() + 15000;
    }

    protected boolean isNotOutdated() {
      return this.expirationTime >= System.currentTimeMillis();
    }

    protected boolean isOutdated() {
      return this.expirationTime < System.currentTimeMillis();
    }
  }

  /**
   * {@link CloudFileAPI} implementation.
   */
  protected class FileAPI extends AbstractFileAPI {

    /**
     * Internal API.
     */
    protected final DropboxAPI api;

    FileAPI() {
      this.api = getUser().api();
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

      String parentId = getParentId(folderNode);
      String title = getTitle(folderNode);
      DbxEntry.Folder folder;
      try {
        folder = api.createFolder(parentId, title);
      } catch (ConflictException e) {
        // XXX we assume name as factor of equality here
        String idPath = getId(folderNode);
        DbxEntry item = api.get(idPath);
        if (item.isFolder()) {
          folder = item.asFolder();
        } else {
          throw e; // we cannot do anything at this level
        }
      }

      String id = idPath(folder.path);
      String name = folder.name;
      String link = api.getUserFolderLink(folder.path);
      String createdBy = currentUserName();
      String modifiedBy = createdBy;
      String type = FOLDER_TYPE;

      initFolder(folderNode, id, name, type, link, createdBy, modifiedBy, created, created); // created as
                                                                                             // modified here
      initDropboxFolder(folderNode, folder.mightHaveThumbnail, folder.iconName, null);

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
      DbxEntry item = move(fileNode);
      if (item != null) {
        if (item.isFile()) {
          DbxEntry.File file = item.asFile();
          DbxFileInfo info = new DbxFileInfo(file.path);
          String id = info.idPath;
          String name = file.name;
          String link = api.getUserFileLink(info.parentPath, info.name);
          String thumbnailLink = null;
          String createdBy = currentUserName();
          String modifiedBy = createdBy;
          String type = findMimetype(name);
          long size = file.numBytes;

          initFile(fileNode,
                   id,
                   name,
                   type,
                   link,
                   null, // see previewLink()
                   thumbnailLink,
                   createdBy,
                   modifiedBy,
                   null,
                   modified,
                   size);
          initDropboxFile(fileNode, file.mightHaveThumbnail, file.iconName, file.rev, size);

          resetSharing(fileNode);

          return new JCRLocalCloudFile(fileNode.getPath(),
                                       id,
                                       name,
                                       link,
                                       null,
                                       previewLink(fileNode),
                                       thumbnailLink,
                                       type,
                                       mimeTypes.getMimeTypeMode(type, name),
                                       createdBy,
                                       modifiedBy,
                                       fileAPI.getCreated(fileNode),
                                       modified,
                                       size,
                                       fileNode,
                                       true);
        } else {
          throw new CloudDriveException("Moved file appears as not a file in Dropbox " + item.path);
        }
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
      DbxEntry item = move(folderNode);
      if (item != null) {
        if (item.isFolder()) {
          DbxEntry.Folder folder = item.asFolder();

          String id = idPath(folder.path);
          String name = folder.name;
          String link = api.getUserFolderLink(folder.path);
          String createdBy = currentUserName();
          String modifiedBy = createdBy;
          String type = FOLDER_TYPE;

          initFolder(folderNode, id, name, type, link, createdBy, modifiedBy, null, modified);
          initDropboxFolder(folderNode, folder.mightHaveThumbnail, folder.iconName, null);

          return new JCRLocalCloudFile(folderNode.getPath(),
                                       id,
                                       name,
                                       link,
                                       type,
                                       modifiedBy,
                                       createdBy,
                                       fileAPI.getCreated(folderNode),
                                       modified,
                                       folderNode,
                                       true);
        } else {
          throw new CloudDriveException("Moved folder appears as not a folder in Dropbox " + item.path);
        }
      } else {
        // else folder don't need to be moved - return local
        return readFile(folderNode);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile updateFileContent(Node fileNode,
                                       Calendar modified,
                                       String mimeType,
                                       InputStream content) throws CloudDriveException, RepositoryException {
      // Update means upload of a new content
      return uploadFile(fileNode, null, modified, mimeType, content, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile copyFile(Node srcFileNode, Node destFileNode) throws CloudDriveException, RepositoryException {
      DbxEntry item = copy(srcFileNode, destFileNode);
      if (item != null) {
        if (item.isFile()) {
          DbxEntry.File file = item.asFile();
          DbxFileInfo info = new DbxFileInfo(file.path);
          String id = info.idPath;
          String name = file.name;
          String link = api.getUserFileLink(info.parentPath, info.name);
          String thumbnailLink = null;
          String createdBy = currentUserName();
          String modifiedBy = createdBy;
          String type = findMimetype(name);
          long size = file.numBytes;
          Calendar created = Calendar.getInstance();

          initFile(destFileNode,
                   id,
                   name,
                   type,
                   link,
                   null, // see previewLink()
                   thumbnailLink,
                   createdBy,
                   modifiedBy,
                   created,
                   created,
                   size);
          initDropboxFile(destFileNode, file.mightHaveThumbnail, file.iconName, file.rev, size);

          resetSharing(destFileNode);

          return new JCRLocalCloudFile(destFileNode.getPath(),
                                       id,
                                       name,
                                       link,
                                       null,
                                       previewLink(destFileNode),
                                       thumbnailLink,
                                       type,
                                       mimeTypes.getMimeTypeMode(type, name),
                                       createdBy,
                                       modifiedBy,
                                       created,
                                       created,
                                       size,
                                       destFileNode,
                                       true);
        } else {
          throw new CloudDriveException("Copied file appears as not a file in Dropbox " + item.path);
        }
      } else {
        if (LOG.isDebugEnabled()) {
          StringBuilder idInfo = new StringBuilder();
          try {
            String idPath = getId(srcFileNode);
            idInfo.append('(').append(idPath);
          } catch (PathNotFoundException e) {
            idInfo.append("???");
          }
          try {
            idInfo.append(" -> ").append(getId(destFileNode)).append(") ");
          } catch (PathNotFoundException e) {
            idInfo.append("???) ");
          }
          LOG.debug("File copy failed in Dropbox without a reason " + idInfo.toString() + destFileNode.getPath());
        }
        // we throw provider exception to let it be retried
        throw new DropboxException("File copy failed in Dropbox without a reason " + destFileNode.getPath());
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile copyFolder(Node srcFolderNode, Node destFolderNode) throws CloudDriveException, RepositoryException {
      DbxEntry item = copy(srcFolderNode, destFolderNode);
      if (item != null) {
        if (item.isFolder()) {
          DbxEntry.Folder folder = item.asFolder();
          String id = idPath(folder.path);
          String name = folder.name;
          String link = api.getUserFolderLink(folder.path);
          String createdBy = currentUserName();
          String modifiedBy = createdBy;
          String type = FOLDER_TYPE;
          Calendar created = Calendar.getInstance();

          initFolder(destFolderNode, id, name, type, link, createdBy, modifiedBy, created, created);
          initDropboxFolder(destFolderNode, folder.mightHaveThumbnail, folder.iconName, null);

          return new JCRLocalCloudFile(destFolderNode.getPath(),
                                       id,
                                       name,
                                       link,
                                       type,
                                       modifiedBy,
                                       createdBy,
                                       created,
                                       created,
                                       destFolderNode,
                                       true);
        } else {
          throw new CloudDriveException("Copied folder appears as not a folder in Dropbox " + item.path);
        }
      } else {
        if (LOG.isDebugEnabled()) {
          StringBuilder idInfo = new StringBuilder();
          try {
            String idPath = getId(srcFolderNode);
            idInfo.append('(').append(idPath);
          } catch (PathNotFoundException e) {
            idInfo.append("???");
          }
          try {
            idInfo.append(" -> ").append(getId(destFolderNode)).append(") ");
          } catch (PathNotFoundException e) {
            idInfo.append("???) ");
          }
          LOG.debug("Folder copy failed in Dropbox without a reason " + idInfo.toString() + destFolderNode.getPath());
        }
        // we throw provider exception to let it be retried
        throw new DropboxException("Folder copy failed in Dropbox without a reason " + destFolderNode.getPath());
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeFile(String id) throws CloudDriveException, RepositoryException {
      api.delete(id);
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeFolder(String id) throws CloudDriveException, RepositoryException {
      api.delete(id);
      return true;
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
      String idPath = getId(fileNode);
      String rev = getRev(fileNode);
      DbxEntry.File file = api.restoreFile(idPath, rev);
      if (file != null) {
        DbxFileInfo info = new DbxFileInfo(file.path);
        String id = info.idPath;
        String name = file.name;
        String link = api.getUserFileLink(info.parentPath, info.name);
        String thumbnailLink = null;
        String createdBy = currentUserName();
        String modifiedBy = createdBy;
        String type = findMimetype(name);
        Calendar modified = Calendar.getInstance();
        long size = file.numBytes;

        initFile(fileNode,
                 id,
                 name,
                 type,
                 link,
                 null, // see previewLink()
                 thumbnailLink,
                 createdBy,
                 modifiedBy,
                 null,
                 modified,
                 size);
        initDropboxFile(fileNode, file.mightHaveThumbnail, file.iconName, file.rev, size);

        return new JCRLocalCloudFile(fileNode.getPath(),
                                     id,
                                     name,
                                     link,
                                     null,
                                     previewLink(fileNode),
                                     thumbnailLink,
                                     type,
                                     mimeTypes.getMimeTypeMode(type, name),
                                     createdBy,
                                     modifiedBy,
                                     fileAPI.getCreated(fileNode),
                                     modified,
                                     size,
                                     fileNode,
                                     true);
      } else {
        // else file with given idPath/rev not found - we cannot keep this file in the drive
        throw new FileRestoreException("File revision (" + rev + ") cannot be restored '" + getTitle(fileNode) + "'");
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile untrashFolder(Node folderNode) throws CloudDriveException, RepositoryException {
      // Untrash folder using local file by file. This seems cannot work for empty folders.
      // traverse over the folder children nodes (files and folders recursively) and restore file by file
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
          childNode.remove();
        }
      }

      // try get the folder from Dropbox, it should appear after child file(s) restoration
      String idPath = getId(folderNode);
      DbxEntry item = api.get(idPath);
      if (item != null) {
        if (item.isFolder()) {
          DbxEntry.Folder folder = item.asFolder();
          String id = idPath(folder.path);
          String name = folder.name;
          String link = api.getUserFolderLink(folder.path);
          String createdBy = currentUserName();
          String modifiedBy = createdBy;
          String type = FOLDER_TYPE;
          Calendar modified = Calendar.getInstance();

          initFolder(folderNode, id, name, type, link, createdBy, modifiedBy, null, modified);
          initDropboxFolder(folderNode, folder.mightHaveThumbnail, folder.iconName, null);

          return new JCRLocalCloudFile(folderNode.getPath(),
                                       id,
                                       name,
                                       link,
                                       type,
                                       modifiedBy,
                                       createdBy,
                                       fileAPI.getCreated(folderNode),
                                       modified,
                                       folderNode,
                                       true);
        } else {
          throw new FileRestoreException("Untrashed folder appears as not a folder in Dropbox " + item.path);
        }
      } else {
        // else folder not restored - we cannot keep it in the drive
        throw new FileRestoreException("Folder cannot be restored '" + getTitle(folderNode) + "'");
      }
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
    public CloudFile restore(String idPath, String nodePath) throws NotFoundException,
                                                             CloudDriveException,
                                                             RepositoryException {

      // FYI the below logic will not work if remote file will change the name or parent during the sync:
      // its Dropbox path will be changed and restore will not be able to find it by known here idPath.
      // Such file should come as part of Delta changes and apply accordingly.

      // 1. find all local files with such idPath
      // 2. should be only one with such idPath, but if have several
      // 2.1 find where parent idPath doesn't match the file idPath, remove such file(s)
      // 2.2 find where the file name doesn't match and remove it
      // 3. restore (create/update) the file and fetch the sub-tree if folder, from Dropbox

      // FYI info build on lower-case path (name and/or parent path can be inaccurate)
      DbxFileInfo fileInfo = new DbxFileInfo(idPath);
      if (fileInfo.isRoot()) {
        // skip root node - this shouldn't happen
        if (LOG.isDebugEnabled()) {
          LOG.debug("Cannot restore root folder - ignore it: " + idPath + " node: " + nodePath);
        }
        return null;
      } else {
        if (LOG.isDebugEnabled()) {
          LOG.debug(">> restore(" + idPath + ", " + nodePath + ")");
        }
      }
      DbxFileInfo parentInfo = fileInfo.getParent();

      JCRLocalCloudFile restored = null;

      // go through all local nodes existing with given file Dropbox path
      // and restore if its parent exists remotely, or remove local node otherwise
      for (Node node : findNodes(Arrays.asList(idPath))) {
        String path = node.getPath();
        if (path.equals(nodePath)) {
          Node parent = node.getParent();
          String parentIdPath = fileAPI.getId(parent);
          if (parentInfo.idPath.equals(parentIdPath)) {
            // it is proper parent where this node should exist - restore it (with sub-tree if folder)
            DbxEntry item = restoreSubtree(api, idPath, parent);
            if (item != null) {
              // if sub-tree restored successfully, restore the file itself
              restored = updateItem(api, fileInfo, item, parent, node);
            } else {
              // this file doesn't exist remotely - remove it
              remove(node);
            }
          } else {
            // this node should not exist on this parent
            remove(node);
          }
        } else {
          // this node should not exist on this path
          remove(node);
        }
      }

      if (restored == null) {
        // nothing restored from existing in the local drive (it's removal usecase),
        // need fetch this file by idPath to its local parent
        DbxEntry remoteParent = api.get(parentInfo.path); // remote can be null
        if (remoteParent != null) {
          for (Node parent : findNodes(Arrays.asList(remoteParent.path))) {
            if (nodePath.startsWith(parent.getPath())) {
              // restore file and a sub-tree if folder, this will not update the parent
              DbxEntry item = restoreSubtree(api, idPath, parent);
              if (item != null) {
                // if sub-tree restored successfully, restore the file itself
                restored = updateItem(api, fileInfo, item, parent, null);
              } // else this file doesn't exist remotely
            } else {
              // unexpected: parent exists on another path than the restoring file - remove it locally
              if (LOG.isDebugEnabled()) {
                LOG.debug("Restoration of " + idPath + " found parent on wrong path " + parent.getPath()
                    + ". Node will be removed.");
              }
              remove(parent);
            }
          }
        } else {
          // parent not found remotely - nothing to restore
          if (LOG.isDebugEnabled()) {
            LOG.debug("Restore not possible: remote parent not found for " + idPath);
          }
        }
      }

      return restored;
    }

    // ********* internals **********

    protected String getRev(Node fileNode) throws RepositoryException {
      return fileNode.getProperty("dropbox:rev").getString();
    }

    protected CloudFile uploadFile(Node fileNode,
                                   Calendar created,
                                   Calendar modified,
                                   String mimeType,
                                   InputStream content,
                                   boolean update) throws CloudDriveException, RepositoryException {

      String parentId = getParentId(fileNode);
      String title = getTitle(fileNode);
      String rev = update ? getRev(fileNode) : null;
      DbxEntry.File file;
      try {
        file = api.uploadFile(parentId, title, content, rev);
      } catch (ConflictException e) {
        String idPath = getId(fileNode);
        DbxEntry item = api.get(idPath);
        if (item != null) {
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
        } else {
          LOG.warn("File upload conflicted but remote file not found " + api.filePath(parentId, title));
          throw e; // we cannot do anything at this level
        }
      }

      DbxFileInfo info = new DbxFileInfo(file.path);
      String id = info.idPath;
      String name = file.name;
      String link = api.getUserFileLink(info.parentPath, info.name);
      String thumbnailLink = null;
      String createdBy = currentUserName();
      String modifiedBy = createdBy;
      String type = findMimetype(name);
      long size = file.numBytes;

      initFile(fileNode,
               id,
               name,
               type,
               link,
               null, // see previewLink()
               thumbnailLink,
               createdBy,
               modifiedBy,
               created,
               modified,
               size);
      initDropboxFile(fileNode, file.mightHaveThumbnail, file.iconName, file.rev, size);

      return new JCRLocalCloudFile(fileNode.getPath(),
                                   id,
                                   title,
                                   link,
                                   null,
                                   previewLink(fileNode),
                                   thumbnailLink,
                                   type,
                                   mimeTypes.getMimeTypeMode(type, name),
                                   createdBy,
                                   modifiedBy,
                                   created,
                                   modified,
                                   size,
                                   fileNode,
                                   true);
    }

    protected void remove(Node node) throws RepositoryException {
      // remove only if not already ignored
      if (!fileAPI.isIgnored(node)) {
        try {
          node.remove();
        } catch (PathNotFoundException e) {
          // already removed
        }
      }
    }

    protected DbxEntry move(Node node) throws RepositoryException,
                                       TooManyFilesException,
                                       DropboxException,
                                       RefreshAccessException,
                                       ConflictException,
                                       NotFoundException {
      // Use move for renaming: given node should be moved (incl renamed) to its new parent

      // It is source path, from where we move the file
      String localIdPath = getId(node);

      String nodePath = node.getPath();
      MovedFile file = moved.get(localIdPath);
      if (file != null && file.nodePath.equals(nodePath) && file.isNotOutdated()) {
        // file was moved here few seconds ago: read it from remote side
        return api.get(file.path);
      } else {
        // it is remote destination parent path
        String localParentIdPath = getParentId(node);

        // file title and lower-case name
        String title = getTitle(node);
        String name = idPath(title);

        boolean isMove;
        if (!localIdPath.startsWith(localParentIdPath)) {
          // idPath of the file doesn't belong to the file current parent - it's moved file
          isMove = true;
        } else if (!localIdPath.endsWith(name)) {
          // idPath of the file has other than current file name (lower-case!) - it's renamed file
          isMove = true;
        } else {
          // otherwise file wasn't changed in meaning of Dropbox structure
          isMove = false;
        }

        if (isMove) {
          // new Dropbox path for the file (use case preserving title)
          String destPath = api.filePath(localParentIdPath, title);

          // FYI move conflicts will be solved by the caller (in core)
          // XXX but there is also a need to check if file wan't already moved as result of failed request
          // (timeout or like that) - see catch of NotFoundException below
          try {
            DbxEntry f = api.move(localIdPath, destPath);
            if (f == null) {
              StringBuilder msg = new StringBuilder();
              msg.append("Move failed in Dropbox without a reason ");
              if (LOG.isDebugEnabled()) {
                StringBuilder dmsg = new StringBuilder();
                dmsg.append(msg).append('(').append(localIdPath).append(") ").append(nodePath);
                LOG.debug(dmsg.toString());
              }
              msg.append(nodePath);
              // we throw provider exception to let it be retried
              throw new DropboxException(msg.toString());
            } else {
              // remember the file to do not move it again in next few seconds (move op in ECMS can do several
              // session saves)
              moved.put(localIdPath, new MovedFile(destPath, nodePath));
              if (f.isFolder()) {
                // need update local sub-tree for a right parent in idPaths
                updateSubtree(node, idPath(destPath));
              }
              return f;
            }
          } catch (NotFoundException e) {
            // this will happen in case if source not found remotely: ensure destination already in place
            DbxEntry f = api.get(destPath);
            if (f != null) {
              // destination already in place, we assume it's our file
              // TODO check hash/CRC to be sure it is precisely the same file
              return f;
            }
            throw e; // throw what we have
          }
        } else {
          return null;
        }
      }
    }

    protected void updateSubtree(Node folderNode, String folderIdPath) throws RepositoryException {
      for (NodeIterator niter = folderNode.getNodes(); niter.hasNext();) {
        Node node = niter.nextNode();
        if (isFile(node)) {
          String title = getTitle(node);
          String idPath = idPath(api.filePath(folderIdPath, title));
          setId(node, idPath);
          resetSharing(node);
          if (isFolder(node)) {
            updateSubtree(node, idPath);
          }
        }
      }
    }

    /**
     * Reset Dropbox file direct/shared link to force generation of a new one within new path. It is a
     * required step when copying or moving file - Dropbox maintains links respectively the file location.
     * 
     * @param fileNode
     * @throws RepositoryException
     */
    protected void resetSharing(Node fileNode) throws RepositoryException {
      // by setting null in JCR we remove the property if it was found
      fileNode.setProperty("dropbox:directLink", (String) null);
      if (fileNode.hasProperty("dropbox:sharedLink")) {
        fileNode.setProperty("dropbox:sharedLinkExpires", System.currentTimeMillis());
      }
    }

    protected DbxEntry copy(Node sourceNode, Node destNode) throws RepositoryException,
                                                            TooManyFilesException,
                                                            DropboxException,
                                                            RefreshAccessException,
                                                            ConflictException,
                                                            NotFoundException {
      // It is source path, from where we move the file
      String sourceIdPath = getId(sourceNode);

      // it is remote destination parent path
      String destParentIdPath = getParentId(destNode);

      // file name
      String title = getTitle(destNode);

      // new Dropbox path for the file (use case preserving title)
      String destPath = api.filePath(destParentIdPath, title);

      // FYI copy conflicts will be solved by the caller (in core)
      DbxEntry f = api.copy(sourceIdPath, destPath);

      if (f != null && f.isFolder()) {
        // need update local sub-tree for a right parent in idPaths
        updateSubtree(destNode, idPath(destPath));
      }

      return f;
    }

  }

  /**
   * An implementation of {@link SyncCommand} based on an abstract deltas queue proposed and maintained by the
   * cloud service.
   * 
   */
  protected class EventsSync extends SyncCommand implements Changes {

    /**
     * Internal API.
     */
    protected final DropboxAPI        api;

    /**
     * Nodes read/created by Dropbox path in this sync.
     */
    protected final Map<String, Node> pathNodes = new HashMap<String, Node>();

    /**
     * Create command for Template synchronization.
     * 
     * @throws RepositoryException
     * @throws DriveRemovedException
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
      // sync algorithm based on deltas from Dropbox API
      long changeId = System.currentTimeMillis(); // time of the begin

      // clean map of moved from expired records (do it here as sync runs on delta changes
      // from remote side - thus will clean periodically)
      cleanExpiredMoved();

      pathNodes.put(DropboxAPI.ROOT_PATH, driveNode);

      String cursor = driveNode.getProperty("dropbox:cursor").getString();

      // buffer all items,
      // apply them in proper order (taking in account parent existence),
      // remove already applied (check by event id in history),
      // apply others to local nodes
      // save just applied deltas as history
      DeltaChanges deltas = api.getDeltas(cursor);

      iterators.add(deltas);

      // loop over deltas and respect this thread interrupted status to cancel the command correctly
      while (deltas.hasNext() && !Thread.currentThread().isInterrupted()) {
        DbxDelta.Entry<DbxEntry> delta = deltas.next();
        DbxEntry item = delta.metadata;
        String deltaPath = delta.lcPath;

        if (item != null) {
          DbxFileInfo file = new DbxFileInfo(item.path); // use case preserved file path
          if (file.isRoot()) {
            // skip root node - this shouldn't happen
            if (LOG.isDebugEnabled()) {
              LOG.debug("Root folder entry found in delta changes - ignore it: " + deltaPath);
            }
            continue;
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.debug(">> delta: " + item.name + (item.isFolder() ? " folder change " : " file change ") + deltaPath);
            }
          }

          // file should exist locally
          Node parent = getParent(file);
          if (parent == null) {
            // parent not found: find nearest existing ancestor and fetch the sub-tree from Dropbox
            Node existingAncestor = getExistingAncestor(file.getParent());
            if (existingAncestor != null) {
              String idPath = fileAPI.getId(existingAncestor);
              // FYI existing ancestor itself will not be updated
              JCRLocalDropboxDrive.this.fetchSubtree(api, idPath, existingAncestor, false, iterators, this);
            } else {
              if (LOG.isDebugEnabled()) {
                LOG.debug("Cannot find existing ancestor in local drive storage. Delta path: " + deltaPath + ". File path: "
                    + item.path);
              }
              throw new CloudDriveException("Cannot find existing parent folder locally for " + item.path);
            }
          } else {
            // found local parent: create/update the file in it
            apply(updateItem(api, file, item, parent, null));
          }
        } else { // need remove local
          DbxFileInfo file = new DbxFileInfo(deltaPath); // we use lower-case path here
          if (file.isRoot()) {
            // skip root node - this shouldn't happen
            if (LOG.isDebugEnabled()) {
              LOG.debug("Root folder entry found in delta changes for removal - ignore it: " + deltaPath);
            }
            continue;
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.debug(">> delta: removed " + deltaPath);
            }
          }
          removeFile(file);
        }
      }

      if (!Thread.currentThread().isInterrupted()) {
        setChangeId(changeId);
        // save cursor explicitly to let use it in next sync even if nothing changed in this one
        Property cursorProp = driveNode.setProperty("dropbox:cursor", deltas.cursor);
        cursorProp.save();
        updateState(deltas.cursor);
        if (LOG.isDebugEnabled()) {
          LOG.debug("<< syncFiles: " + driveNode.getPath() + "\n\r" + cursor + " --> " + deltas.cursor);
        }
      }
    }

    /**
     * {@inheritDoc}
     */
    public void apply(JCRLocalCloudFile local) throws RepositoryException, CloudDriveException {
      if (local.isChanged()) {
        removeRemoved(local.getPath());
        addChanged(local);
      }
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
     * Find file node by its Dropbox path (lower-case or natural form).
     * 
     * @param path {@link String}
     * @return {@link Node}
     * @throws RepositoryException
     * @throws CloudDriveException
     */
    protected Node getFile(DbxFileInfo file) throws RepositoryException, CloudDriveException {
      // FYI Drive root nodes already in the map
      Node node = pathNodes.get(file.idPath);
      if (node == null) {
        // read from local storage
        node = readNode(file);
      }
      return node;
    }

    /**
     * Remove file or folder node by its Dropbox path (lower-case or natural form).
     * 
     * @param file {@link DbxFileInfo}
     * @return <code>true</code> if file found and successfully removed, <code>false</code> otherwise
     * @throws RepositoryException
     * @throws CloudDriveException
     */
    protected boolean removeFile(DbxFileInfo file) throws RepositoryException, CloudDriveException {
      Node node = pathNodes.remove(file.idPath);
      if (node == null) {
        // read from local storage
        node = readNode(file);
      }
      if (node != null) {
        removeLinks(node); // explicitly remove file links outside the drive
        String nodePath = node.getPath();
        node.remove();
        addRemoved(nodePath);
        return true;
      }
      return false;
    }

    /**
     * Find nearest existing ancestor of the file using its Dropbox path (lower-case or natural form).
     * 
     * @param file {@link DbxFileInfo}
     * @return {@link Node}
     * @throws RepositoryException
     * @throws CloudDriveException
     * @throws IllegalArgumentException when file argument is a root drive of Dropbox
     */
    protected Node getExistingAncestor(DbxFileInfo file) throws RepositoryException,
                                                         CloudDriveException,
                                                         IllegalArgumentException {
      if (file.isRoot()) {
        Node node;
        DbxFileInfo parent = file.getParent();
        if (parent.isRoot()) {
          // it's root node as parent - we already have it
          node = driveNode;
        } else {
          node = getFile(parent);
          if (node == null) {
            node = getExistingAncestor(parent);
          }
        }
        return node;
      } else {
        // root node as a parent
        return driveNode;
      }
    }

    protected Node readNode(DbxFileInfo file) throws RepositoryException, CloudDriveException {
      Node parent = getParent(file);
      if (parent != null) {
        Node node = JCRLocalDropboxDrive.this.readNode(parent, file.name, file.idPath);
        if (node != null) {
          pathNodes.put(file.idPath, node);
        }
        return node;
      } else {
        return null;
      }
    }

    /**
     * Return parent node of the given file path.
     * 
     * @param file {@link DbxFileInfo}
     * @return {@link Node}
     * @throws RepositoryException
     * @throws CloudDriveException
     */
    protected Node getParent(DbxFileInfo file) throws RepositoryException, CloudDriveException {
      Node parent;
      if (file.isRoot()) {
        parent = driveNode;
      } else {
        parent = getFile(file.getParent());
      }
      return parent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void preSaveChunk() throws CloudDriveException, RepositoryException {
      // nothing save for full sync
    }
  }

  public class DropboxState extends DriveState {

    final String cursor;

    final String url;

    final int    timeout;

    protected DropboxState(String cursor) {
      this.cursor = cursor;
      this.timeout = DropboxAPI.DELTA_LONGPOLL_TIMEOUT;
      this.url = new StringBuilder(DropboxAPI.DELTA_LONGPOLL_URL).append("?cursor=")
                                                                 .append(cursor)
                                                                 .append("&timeout=")
                                                                 .append(timeout)
                                                                 .toString();
    }

    /**
     * @return the cursor
     */
    public String getCursor() {
      return cursor;
    }

    /**
     * @return the url
     */
    public String getUrl() {
      return url;
    }

    /**
     * @return the timeout
     */
    public int getTimeout() {
      return timeout;
    }
  }

  /**
   * Dropbox file info (path as ID, name and parent path) extracted from its original path in Dropbox.
   */
  protected class DbxFileInfo {
    /**
     * File name in natural form as in Dropbox.
     */
    protected final String name;

    /**
     * File path in natural form as in Dropbox (case-preserved).
     */
    protected final String path;

    /**
     * Parent path (case-preserved). It cannot be used as idPath!
     */
    protected final String parentPath;

    /**
     * Path in lower-case, can be used as file ID.
     */
    protected final String idPath;

    /**
     * Lazy obtained parent info instance.
     */
    protected DbxFileInfo  parent;

    protected DbxFileInfo(String dbxPath) {
      if (dbxPath == null) {
        throw new NullPointerException("Null path not allowed");
      }
      if (dbxPath.length() == 0) {
        throw new IllegalArgumentException("Empty path not allowed");
      }

      this.path = dbxPath;
      this.idPath = idPath(dbxPath);

      String name;
      String parentPath;
      if (DropboxAPI.ROOT_PATH.equals(this.idPath)) {
        // it's root of the drive
        name = null;
        parentPath = null;
      } else {
        int parentEndIndex = this.path.lastIndexOf('/');
        int endIndex;
        if (parentEndIndex == this.path.length() - 1) {
          // for cases with ending slash (e.g. /my/path/ and we need /my parent)
          endIndex = parentEndIndex;
          parentEndIndex = this.path.lastIndexOf('/', endIndex - 1);
        } else {
          endIndex = this.path.length();
        }
        // name and parent path case-preserved (it cannot be used as idPath!)
        if (parentEndIndex > 0) {
          parentPath = this.path.substring(0, parentEndIndex);
          int nameIndex = parentEndIndex + 1;
          if (nameIndex < endIndex) {
            name = this.path.substring(nameIndex, endIndex);
          } else {
            name = null;
          }
        } else if (parentEndIndex == 0) {
          // it's root node as parent
          parentPath = DropboxAPI.ROOT_PATH;
          name = this.path.substring(parentEndIndex + 1, endIndex);
        } else {
          // we guess it's root node as parent and given path a name of file
          parentPath = DropboxAPI.ROOT_PATH;
          name = this.path;
        }
      }

      this.name = name;
      this.parentPath = parentPath;
    }

    protected DbxFileInfo getParent() {
      if (isRoot()) {
        return null;
      } else {
        if (parent == null) {
          parent = new DbxFileInfo(parentPath);
        }
        return parent;
      }
    }

    protected boolean isRoot() {
      return parentPath == null;
    }
  }

  /**
   * Dropbox drive state. See {@link #getState()}.
   */
  protected DropboxState           state;

  /**
   * Moved files with expiration on each sync run.
   */
  protected Map<String, MovedFile> moved = new ConcurrentHashMap<String, MovedFile>();

  /**
   * @param user
   * @param driveNode
   * @param sessionProviders
   * @throws CloudDriveException
   * @throws RepositoryException
   */
  protected JCRLocalDropboxDrive(DropboxUser user,
                                 Node driveNode,
                                 SessionProviderService sessionProviders,
                                 NodeFinder finder,
                                 ExtendedMimeTypeResolver mimeTypes) throws CloudDriveException, RepositoryException {
    super(user, driveNode, sessionProviders, finder, mimeTypes);
    getUser().api().getToken().addListener(this);
  }

  protected JCRLocalDropboxDrive(API apiBuilder,
                                 DropboxProvider provider,
                                 Node driveNode,
                                 SessionProviderService sessionProviders,
                                 NodeFinder finder,
                                 ExtendedMimeTypeResolver mimeTypes) throws RepositoryException, CloudDriveException {
    super(loadUser(apiBuilder, provider, driveNode), driveNode, sessionProviders, finder, mimeTypes);
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
    driveNode.setProperty("ecd:id", DropboxAPI.ROOT_PATH);
    driveNode.setProperty("ecd:url", DropboxAPI.ROOT_URL);
  }

  protected void updateState(String cursor) {
    this.state = new DropboxState(cursor);
  }

  /**
   * Load user from the drive Node.
   * 
   * @param apiBuilder {@link API} API builder
   * @param provider {@link DropboxProvider}
   * @param driveNode {@link Node} root of the drive
   * @return {@link DropboxUser}
   * @throws RepositoryException
   * @throws DropboxException
   * @throws CloudDriveException
   */
  protected static DropboxUser loadUser(API apiBuilder, DropboxProvider provider, Node driveNode) throws RepositoryException,
                                                                                                  DropboxException,
                                                                                                  CloudDriveException {
    String username = driveNode.getProperty("ecd:cloudUserName").getString();
    String email = driveNode.getProperty("ecd:userEmail").getString();
    String userId = driveNode.getProperty("ecd:cloudUserId").getString();

    String accessToken = driveNode.getProperty("dropbox:oauth2AccessToken").getString();
    DropboxAPI driveAPI = apiBuilder.load(accessToken).build();

    return new DropboxUser(userId, username, email, provider, driveAPI);
  }

  /**
   * {@inheritDoc}
   * 
   * @throws DropboxException
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
   */
  @Override
  public ContentReader getFileContent(String idPath) throws RepositoryException, CloudDriveException {
    DropboxAPI api = getUser().api();
    Downloader downloader = api.getContent(idPath);
    if (downloader != null && downloader.metadata.isFile()) {
      DbxEntry.File file = downloader.metadata.asFile();

      String type = findMimetype(file.name);
      String typeMode = mimeTypes.getMimeTypeMode(type, file.name);
      return new CloudFileContent(file.name, downloader.body, type, typeMode, file.numBytes);
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FilesState getState() throws DriveRemovedException,
                               RefreshAccessException,
                               CloudProviderException,
                               RepositoryException {
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
    // Calendar now = Calendar.getInstance();
    // Calendar last = rootNode().getProperty("dropbox:changeDate").getDate();
    // XXX we force a full sync (a whole drive traversing) each defined period.
    // We do this for a case when provider will not provide a full history for files connected long time ago
    // and weren't synced day by day (drive was rarely used).
    // if (now.getTimeInMillis() - last.getTimeInMillis() < FULL_SYNC_PERIOD) {
    return new EventsSync();
    // } else {
    // return new FullSync();
    // }
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
    // TODO implement this method if Cloud API requires explicit forcing of access token renewal check
    // Some APIes do this check internally on each call (then do nothing here), others may need explicit
    // forcing of the check (then call the API check from here) -
    // follow the API docs to find required behviour for this method.
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void updateAccess(CloudUser newUser) throws CloudDriveException, RepositoryException {
    getUser().api().updateToken(((DropboxUser) newUser).api().getToken());
  }

  /**
   * Initialize cloud's common specifics of files and folders.
   * 
   * @param localNode {@link Node}
   * @param item {@link Object}
   * @throws RepositoryException
   * @throws DropboxException
   */
  @Deprecated // Nothing common to init for Dropbox
  protected void initCloudItem(Node localNode, Object item) throws RepositoryException, DropboxException {
    // TODO init localNode with a data of cloud item

    // Etag and sequence_id used for synchronization
    localNode.setProperty("dropbox:etag", "item.getEtag()");
    try {
      String sequenceIdStr = ""; // item.getSequenceId();
      if (sequenceIdStr != null) {
        localNode.setProperty("dropbox:sequenceId", Long.parseLong(sequenceIdStr));
      } // else, it's null (root or trash)
    } catch (NumberFormatException e) {
      throw new DropboxException("Error parsing sequence_id of " + localNode.getPath(), e);
    }

    // File/folder size
    // TODO exo's property to show the size: jcr:content's length?
    localNode.setProperty("ecd:size", ""); // item.getSize()

    // properties below not actually used by the Cloud Drive,
    // they are just for information available to PLF user
    localNode.setProperty("dropbox:ownedBy", ""); // item.getOwnedBy().getLogin()
    localNode.setProperty("dropbox:description", ""); // item.getDescription()
  }

  /**
   * Initialize Dropbox file.
   * 
   * @param localNode {@link Node}
   * @param file {@link DbxEntry.File}
   * @throws RepositoryException
   * @throws DropboxException
   */
  protected void initDropboxFile(Node localNode,
                                 Boolean mightHaveThumbnail,
                                 String iconName,
                                 String rev,
                                 Long size) throws RepositoryException, DropboxException {
    // File revision for synchronization
    localNode.setProperty("dropbox:rev", rev);

    // File size
    // TODO exo's property to show the size: jcr:content's length?
    localNode.setProperty("ecd:size", size);

    initDropboxCommon(localNode, mightHaveThumbnail, iconName);
  }

  /**
   * Initialize Dropbox folder.
   * 
   * @param localNode {@link Node}
   * @param mightHaveThumbnail {@link Boolean}
   * @param iconName {@link String}
   * @param deltaHash String a hash of last Delta synchronization, if <code>null</code> it will be ignored
   * @throws RepositoryException
   * @throws DropboxException
   */
  protected void initDropboxFolder(Node localNode,
                                   Boolean mightHaveThumbnail,
                                   String iconName,
                                   String deltaHash) throws RepositoryException, DropboxException {
    if (deltaHash != null) {
      localNode.setProperty("dropbox:hash", deltaHash);
    }
    initDropboxCommon(localNode, mightHaveThumbnail, iconName);
  }

  /**
   * Initialize Dropbox entry (commons for file and folder).
   * 
   * @param localNode {@link Node}
   * @param mightHaveThumbnail {@link Boolean}
   * @param iconName {@link String}
   * @throws RepositoryException
   * @throws DropboxException
   */
  protected void initDropboxCommon(Node localNode, Boolean mightHaveThumbnail, String iconName) throws RepositoryException,
                                                                                                DropboxException {
    // tells if thumbnail available in Dropbox /thumbnails service
    localNode.setProperty("dropbox:mightHaveThumbnail", mightHaveThumbnail);
    // icon name in Dropbox's icon set
    localNode.setProperty("dropbox:iconName", iconName);
  }

  /**
   * Update or create a local node of Cloud File. If the node is <code>null</code> then it will be open on the
   * given parent and created if not already exists.
   * 
   * @param api {@link DropboxAPI}
   * @param file {@link DbxFileInfo}
   * @param metadata {@link DbxEntry}
   * @param parent {@link Node}
   * @param node {@link Node}, can be <code>null</code>
   * @return {@link JCRLocalCloudFile}
   * @throws RepositoryException for storage errors
   * @throws CloudDriveException for drive or format errors
   */
  protected JCRLocalCloudFile updateItem(DropboxAPI api,
                                         DbxFileInfo file,
                                         DbxEntry metadata,
                                         Node parent,
                                         Node node) throws RepositoryException, CloudDriveException {
    String id = file.idPath; // path as ID
    String title = metadata.name; // title as natural item name in Dropbox (case preserved)
    boolean isFolder = metadata.isFolder();

    String createdBy;
    String modifiedBy;

    // TODO it's possible to get name of modifier on Dropbox, it will be in metadata response, but Java SDK
    // doesn't read it - need extend its Entry and add such fields (and exception handling)
    createdBy = modifiedBy = currentUserName();

    Calendar created;
    Calendar modified;
    boolean changed;
    String type;
    String link, thumbnailLink;
    JCRLocalCloudFile localFile;
    if (isFolder) {
      DbxEntry.Folder dbxFolder = metadata.asFolder();

      // read/create local node if not given
      // Dropbox docs say:
      // If the new entry is a folder, check what your local state has at <path>. If it's a file, replace
      // it with the new entry. If it's a folder, apply the new <metadata> to the folder, but don't modify
      // the folder's children. If your local state doesn't yet include this path, create it as a folder.
      if (node == null) {
        node = openFolder(id, file.name, parent);
      }
      if (!fileAPI.isFolder(node)) {
        node.remove();
        node = openFolder(id, file.name, parent);
      }

      if (node.isNew()) {
        changed = true;
        created = modified = Calendar.getInstance();
        type = FOLDER_TYPE;
        link = api.getUserFolderLink(dbxFolder.path);
        // embedLink = api.getEmbedLink(dbxFolder.path);
        // thumbnailLink = api.getThumbnailLink(dbxFolder.path);
        initFolder(node, id, title, type, link, createdBy, modifiedBy, created, modified);
        initDropboxFolder(node, dbxFolder.mightHaveThumbnail, dbxFolder.iconName, null);
      } else {
        // only "changed" value actual
        changed = false;
        created = modified = null; // will not change stored locally
        type = null;
        link = thumbnailLink = null;
      }

      localFile = new JCRLocalCloudFile(node.getPath(),
                                        id,
                                        title,
                                        link(node),
                                        type,
                                        modifiedBy,
                                        createdBy,
                                        created,
                                        modified,
                                        node,
                                        changed);
    } else {
      DbxEntry.File dbxFile = metadata.asFile();

      // read/create local node if not given
      // Dropbox docs say:
      // If the new entry is a file, replace whatever your local state has at path with the new entry.
      if (node == null) {
        node = openFile(id, file.name, parent);
      }
      if (fileAPI.isFolder(node)) {
        node.remove();
        node = openFile(id, file.name, parent);
      }

      String typeMode;
      if (node.isNew() || !node.getProperty("dropbox:rev").getString().equals(dbxFile.rev)) {
        changed = true;
        created = Calendar.getInstance();
        created.setTime(dbxFile.clientMtime);
        modified = Calendar.getInstance();
        modified.setTime(dbxFile.lastModified);

        type = findMimetype(title);
        // TODO type mode not required if provider's preview/edit will be used (embedded in eXo)
        typeMode = mimeTypes.getMimeTypeMode(type, title);

        link = api.getUserFileLink(file.parentPath, dbxFile.name);
        thumbnailLink = null; // TODO use eXo Thumbnails services in conjunction with Dropbox thumbs

        initFile(node,
                 id,
                 title,
                 type,
                 link,
                 null, // see previewLink()
                 thumbnailLink,
                 createdBy,
                 modifiedBy,
                 created,
                 modified,
                 dbxFile.numBytes);
        initDropboxFile(node, dbxFile.mightHaveThumbnail, dbxFile.iconName, dbxFile.rev, dbxFile.numBytes);
        // DbxUrlWithExpiration dbxLink = api.getDirectLink(dbxFile.path);
        // Temporary link expiration for a file content preview
        // localNode.setProperty("dropbox:linkExpires", dbxLink.expires.getTime());
      } else {
        // only "changed" value actual
        changed = false;
        created = modified = null;
        type = typeMode = null;
        link = thumbnailLink = null;
      }

      localFile = new JCRLocalCloudFile(node.getPath(),
                                        id,
                                        title,
                                        link(node),
                                        null,
                                        previewLink(node),
                                        thumbnailLink,
                                        type,
                                        typeMode,
                                        createdBy,
                                        modifiedBy,
                                        created,
                                        modified,
                                        dbxFile.numBytes,
                                        node,
                                        changed);
    }
    return localFile;
  }

  protected String createSharedLink(Node fileNode) throws RepositoryException, CloudDriveException {
    String idPath = fileAPI.getId(fileNode);
    // obtain shared link from Dropbox
    // WARN this call goes under the drive owner credentials on Dropbox
    DbxUrlWithExpiration dbxLink = getUser().api().getSharedLink(idPath);
    if (dbxLink.url != null) {
      jcrListener.disable();
      try {
        Property dlProp = fileNode.setProperty("dropbox:sharedLink", dbxLink.url);
        long sharedLinkExpires;
        if (dbxLink.expires != null) {
          sharedLinkExpires = dbxLink.expires.getTime();
        } else {
          sharedLinkExpires = System.currentTimeMillis() + DEFAULT_LINK_EXPIRATION_PERIOD;
        }
        Property dleProp = fileNode.setProperty("dropbox:sharedLinkExpires", sharedLinkExpires);
        if (dlProp.isNew()) {
          if (!fileNode.isNew() && !fileNode.isModified()) { // save only if node was already saved
            fileNode.save();
          } // otherwise, it should be saved where the node added/modified (by the owner)
        } else {
          // save only direct link properties for !
          dlProp.save();
          dleProp.save();
        }
        return dbxLink.url;
      } finally {
        jcrListener.enable();
      }
    } else {
      throw new CloudDriveException("Cannot share Dropbox file: null shared link returned for " + idPath);
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
        if (expires > System.currentTimeMillis()) {
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
          // naturally in result of navigation in Documents explorer (except of explicit path pointing what is
          // not possible for human usecases)
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
  protected String previewLink(Node fileNode) throws RepositoryException {
    String idPath = fileAPI.getId(fileNode);
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
      DbxUrlWithExpiration dbxLink = getUser().api().getDirectLink(idPath);
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
      Property dlProp = node.setProperty("dropbox:directLink", directLink = dbxLink.url);
      Property dleProp = node.setProperty("dropbox:directLinkExpires", dbxLink.expires.getTime());
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
    } catch (DriveRemovedException e) {
      LOG.warn("Error getting direct link of Dropbox file " + idPath + ": " + e.getMessage(), e);
    } catch (DropboxException e) {
      LOG.error("Error getting direct link of Dropbox file " + idPath, e);
    } catch (RefreshAccessException e) {
      LOG.warn("Cannot getting direct link of Dropbox file " + idPath + ": authorization required.", e);
    } finally {
      jcrListener.enable();
    }
    // by default, we'll stream the file content via eXo REST service link
    // TODO in case of non-viewable document this will do bad UX (a download popup will appear)
    return ContentService.contentLink(rootWorkspace, fileNode.getPath(), idPath);
  }

  protected boolean notInRange(String path, Collection<String> range) {
    for (String p : range) {
      if (path.startsWith(p)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Return Dropbox path in guaranteed lower case.
   * 
   * @param dbxPath {@link String} Dropbox file path
   * @return {@link String} path in lower-case
   */
  protected String idPath(String dbxPath) {
    String loPath = dbxPath.toUpperCase().toLowerCase();
    return loPath;
  }

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

  protected DbxEntry restoreSubtree(DropboxAPI api, String idPath, Node node) throws CloudDriveException,
                                                                              RepositoryException {
    return fetchSubtree(api, idPath, node, false, null, null);
  }

  protected DbxEntry fetchSubtree(DropboxAPI api,
                                  String idPath,
                                  Node node,
                                  boolean useHash,
                                  Collection<ChunkIterator<?>> iterators,
                                  Changes changes) throws CloudDriveException, RepositoryException {
    String hash = useHash ? folderHash(node) : null;
    FileMetadata items = api.getWithChildren(idPath, hash);
    if (items != null) {
      if (iterators != null) {
        iterators.add(items);
      }
      if (items.changed) {
        while (items.hasNext()) {
          DbxEntry item = items.next();
          DbxFileInfo file = new DbxFileInfo(item.path);
          if (file.isRoot()) {
            // skip root node - this shouldn't happen
            if (LOG.isDebugEnabled()) {
              LOG.debug("Fetched root folder entry - ignore it: " + item.path);
            }
            continue;
          }
          if (changes == null || changes.canApply(idPath, file.idPath)) {
            JCRLocalCloudFile localItem = updateItem(api, file, item, node, null);
            if (changes != null && localItem.isChanged()) {
              changes.apply(localItem);
            }
            if (localItem.isFolder()) {
              // go recursive to the folder
              fetchSubtree(api, localItem.getId(), localItem.getNode(), useHash, iterators, changes);
            }
          }
        }
        if (items.target.isFolder()) {
          DbxEntry.Folder folder = items.target.asFolder();
          initDropboxFolder(node, folder.mightHaveThumbnail, folder.iconName, items.hash);
        } else {
          DbxEntry.File file = items.target.asFile();
          initDropboxFile(node, file.mightHaveThumbnail, file.iconName, file.rev, file.numBytes);
        }
        return items.target;
      }
    }
    // null can mean two things: item not found remotely or has not changed (if hash provided)
    return null;
  }

  protected String folderHash(Node node) throws RepositoryException {
    String hash;
    if (fileAPI.isFolder(node)) {
      try {
        hash = node.getProperty("dropbox:hash").getString();
      } catch (PathNotFoundException e) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Folder hash not found in the node: " + node.getPath());
        }
        hash = null;
      }
    } else {
      hash = null;
    }
    return hash;
  }

  protected void cleanExpiredMoved() {
    for (Iterator<MovedFile> fiter = moved.values().iterator(); fiter.hasNext();) {
      MovedFile file = fiter.next();
      if (file.isOutdated()) {
        fiter.remove();
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String nodeName(String title) {
    // all node names lower-case for Dropbox files
    return super.nodeName(idPath(title));
  }

  /**
   * Ensure the cloud file node has name in lower-case. If name requires change it will be renamed in the
   * current session.<br>
   * NOTE: this method doesn't check if it is a cloud file and doesn't respect JCR namespaces and will check
   * against the whole name of the file.
   * 
   * @param fileNode {@link Node}
   * @return {@link Node} the same as given or renamed to lower-case name.
   * @throws RepositoryException
   */
  protected Node normalizeName(Node fileNode) throws RepositoryException {
    String jcrName = fileNode.getName();
    String lcName = idPath(fileAPI.getTitle(fileNode));
    if (!lcName.equals(jcrName)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Normalizing node name: " + jcrName + " -> " + lcName);
      }
      fileNode.getSession().move(fileNode.getPath(), fileNode.getParent().getPath() + "/" + lcName);
    }
    return fileNode;
  }
}
