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

import com.box.sdk.BoxEvent;
import com.box.sdk.BoxFile;
import com.box.sdk.BoxFolder;
import com.box.sdk.BoxItem;
import com.box.sdk.BoxResource;
import com.box.sdk.BoxSharedLink;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudFile;
import org.exoplatform.clouddrive.CloudFileAPI;
import org.exoplatform.clouddrive.CloudProviderException;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.ConflictException;
import org.exoplatform.clouddrive.ConstraintException;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.SyncNotSupportedException;
import org.exoplatform.clouddrive.box.BoxAPI.ChangesLink;
import org.exoplatform.clouddrive.box.BoxAPI.EventsIterator;
import org.exoplatform.clouddrive.box.BoxAPI.ItemsIterator;
import org.exoplatform.clouddrive.box.BoxConnector.API;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudDrive;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudFile;
import org.exoplatform.clouddrive.jcr.NodeFinder;
import org.exoplatform.clouddrive.oauth2.UserToken;
import org.exoplatform.clouddrive.oauth2.UserTokenRefreshListener;
import org.exoplatform.clouddrive.utils.ExtendedMimeTypeResolver;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;

/**
 * Local drive for Box.
 * 
 * Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: JCRLocalBoxDrive.java 00000 Aug 30, 2013 pnedonosko $
 * 
 */
public class JCRLocalBoxDrive extends JCRLocalCloudDrive implements UserTokenRefreshListener {

  /**
   * Period to perform {@link FullSync} as a next sync request. See implementation of
   * {@link #getSyncCommand()}.
   */
  public static final long FULL_SYNC_PERIOD = 24 * 60 * 60 * 60 * 1000; // 24hrs

  /**
   * Connect algorithm for Box drive.
   */
  protected class Connect extends ConnectCommand {

    /** The api. */
    protected final BoxAPI api;

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
      // call Events service before the actual fetch of Box files,
      // this will provide us a proper streamPosition to start sync from later
      EventsIterator eventsInit = api.getEvents(BoxAPI.STREAM_POSITION_NOW);

      BoxFolder.Info boxRoot = fetchChilds(BoxAPI.BOX_ROOT_ID, driveNode);
      initBoxItem(driveNode, boxRoot); // init parent

      // actual drive URL (its root folder's id), see initDrive() also
      driveNode.setProperty("ecd:url", api.getLink(boxRoot));

      // sync stream
      setChangeId(eventsInit.getNextStreamPosition());
      driveNode.setProperty("box:streamHistory", ""); // empty history
    }

    /**
     * Fetch childs.
     *
     * @param fileId the file id
     * @param parent the parent
     * @return the box folder. info
     * @throws CloudDriveException the cloud drive exception
     * @throws RepositoryException the repository exception
     */
    protected BoxFolder.Info fetchChilds(String fileId, Node parent) throws CloudDriveException, RepositoryException {
      ItemsIterator items = api.getFolderItems(fileId);
      iterators.add(items);
      while (items.hasNext()) {
        BoxItem.Info item = items.next();
        if (!isConnected(fileId, item.getID())) { // work if not already connected
          JCRLocalCloudFile localItem = updateItem(api, item, parent, null);
          if (localItem.isChanged()) {
            addConnected(fileId, localItem);
            if (localItem.isFolder()) {
              // go recursive to the folder
              fetchChilds(localItem.getId(), localItem.getNode());
            }
          } else {
            throw new BoxFormatException("Fetched item was not added to local drive storage");
          }
        }
      }
      return items.getParent();
    }
  }

  /**
   * Sync algorithm for Box drive based on all remote files traversing: we do
   * compare all remote files with locals by its Etag and fetch an item if the tags differ.
   */
  protected class FullSync extends SyncCommand {

    /**
     * Box API.
     */
    protected final BoxAPI api;

    /**
     * Create command for Box synchronization.
     *
     * @throws RepositoryException the repository exception
     * @throws DriveRemovedException the drive removed exception
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
      // real all local nodes of this drive
      readLocalNodes();

      // call Events service before the actual fetch of Box files,
      // this will provide us a proper streamPosition to start sync from later
      EventsIterator eventsInit = api.getEvents(BoxAPI.STREAM_POSITION_NOW);

      // sync with cloud
      BoxFolder.Info boxRoot = syncChilds(BoxAPI.BOX_ROOT_ID, driveNode);
      initBoxItem(driveNode, boxRoot); // init parent

      // sync stream
      setChangeId(eventsInit.getNextStreamPosition());
      driveNode.setProperty("box:streamHistory", "");

      // remove local nodes of files not existing remotely, except of root
      nodes.remove(BoxAPI.BOX_ROOT_ID);
      for (Iterator<List<Node>> niter = nodes.values().iterator(); niter.hasNext()
          && !Thread.currentThread().isInterrupted();) {
        List<Node> nls = niter.next();
        niter.remove();
        for (Node n : nls) {
          String npath = n.getPath();
          if (notInRange(npath, getRemoved())) {
            // remove file links outside the drive, then the node itself
            removeNode(n);
            addRemoved(npath);
          }
        }
      }
    }

    /**
     * Sync childs.
     *
     * @param folderId the folder id
     * @param parent the parent
     * @return the box folder. info
     * @throws RepositoryException the repository exception
     * @throws CloudDriveException the cloud drive exception
     */
    protected BoxFolder.Info syncChilds(String folderId, Node parent) throws RepositoryException, CloudDriveException {

      ItemsIterator items = api.getFolderItems(folderId);
      iterators.add(items);
      while (items.hasNext() && !Thread.currentThread().isInterrupted()) {
        BoxItem.Info item = items.next();

        JCRLocalCloudFile localItem = updateItem(api, item, parent, null);
        if (localItem.isChanged()) {
          addChanged(localItem);
        }

        // cleanup of this file located in another place (usecase of rename/move)
        // XXX this also assumes that Box doesn't support linking of files to other folders
        // remove from map of local to mark the item as existing
        List<Node> existing = nodes.remove(item.getID());
        if (existing != null) {
          String path = localItem.getPath();
          for (Iterator<Node> eiter = existing.iterator(); eiter.hasNext();) {
            Node enode = eiter.next();
            String epath = enode.getPath();
            if (!epath.equals(path) && notInRange(epath, getRemoved())) {
              // remove file links outside the drive, then the node itself
              removeLocalNode(enode);
              // TODO this will be done by removeLocalNode()
              //addRemoved(epath);
              //eiter.remove();
            }
          }
        }

        if (localItem.isFolder()) {
          // go recursive to the folder
          syncChilds(localItem.getId(), localItem.getNode());
        }
      }
      return items.getParent();
    }

    /**
     * Execute full sync from current thread.
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
    protected void preSaveChunk() throws CloudDriveException, RepositoryException {
      // nothing to save for full sync
    }
  }

  /**
   * The Class FileAPI.
   */
  protected class FileAPI extends AbstractFileAPI {

    /**
     * Box service API.
     */
    protected final BoxAPI api;

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
    public CloudFile createFile(Node fileNode,
                                Calendar created,
                                Calendar modified,
                                String mimeType,
                                InputStream content) throws CloudDriveException, RepositoryException {

      String parentId = getParentId(fileNode);
      String title = getTitle(fileNode);
      BoxFile.Info file;
      try {
        file = api.createFile(parentId, title, created, content);
      } catch (ConflictException e) {
        // we assume name as factor of equality here and make local file to reflect the cloud side
        BoxFile.Info existing = null;
        ItemsIterator files = api.getFolderItems(parentId);
        while (files.hasNext()) {
          BoxItem.Info item = files.next();
          if (item instanceof BoxFile.Info && title.equals(item.getName())) {
            existing = (BoxFile.Info) item;
            break;
          }
        }
        if (existing == null) {
          throw e; // we cannot do anything at this level
        } else {
          file = existing;
          // and erase local file data here
          // TODO check data checksum before erasing to ensure it is actually the same
          if (fileNode.hasNode("jcr:content")) {
            fileNode.getNode("jcr:content").setProperty("jcr:data", DUMMY_DATA); // empty data by default
          }
        }
      }

      String id = file.getID();
      String name = file.getName();
      String type = findMimetype(name);
      String link = api.getLink(file);
      String embedLink = api.getEmbedLink(file);
      String thumbnailLink = api.getThumbnailLink(file);
      String createdBy = file.getCreatedBy().getLogin();
      String modifiedBy = file.getModifiedBy().getLogin();
      long size = Math.round(file.getSize());

      initFile(fileNode,
               id,
               name,
               type,
               link,
               embedLink, //
               thumbnailLink, // thumbnailLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified,
               size);
      initBoxItem(fileNode, file);

      return new JCRLocalCloudFile(fileNode.getPath(),
                                   id,
                                   name,
                                   link,
                                   previewLink(null, fileNode),
                                   thumbnailLink,
                                   type,
                                   null,
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
      BoxFolder.Info folder;
      try {
        folder = api.createFolder(getParentId(folderNode), getTitle(folderNode), created);
      } catch (ConflictException e) {
        // we assume name as factor of equality here
        BoxFolder.Info existing = null;
        ItemsIterator files = api.getFolderItems(parentId);
        while (files.hasNext()) {
          BoxItem.Info item = files.next();
          if (item instanceof BoxFolder.Info && title.equals(item.getName())) {
            existing = (BoxFolder.Info) item;
            break;
          }
        }
        if (existing == null) {
          throw e; // we cannot do anything at this level
        } else {
          folder = existing;
        }
      }

      String id = folder.getID();
      String name = folder.getName();
      String type = BoxAPI.FOLDER_TYPE;
      String link = api.getLink(folder);
      String createdBy = folder.getCreatedBy().getLogin();
      String modifiedBy = folder.getModifiedBy().getLogin();

      initFolder(folderNode,
                 id,
                 name,
                 type, //
                 link, // link
                 createdBy, // author
                 modifiedBy, // lastUser
                 created,
                 created); // created as modified here
      initBoxItem(folderNode, folder);

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
      // Update existing file metadata and parent (location).
      BoxFile.Info file = api.updateFile(getParentId(fileNode), getId(fileNode), getTitle(fileNode));

      String id = file.getID();
      String name = file.getName();
      String type = findMimetype(name);
      String link = api.getLink(file);
      String embedLink = api.getEmbedLink(file);
      String thumbnailLink = api.getThumbnailLink(file);
      String createdBy = file.getCreatedBy().getLogin();
      Calendar created = Calendar.getInstance();
      created.setTime(file.getCreatedAt());
      modified = Calendar.getInstance();
      modified.setTime(file.getModifiedAt());
      String modifiedBy = file.getModifiedBy().getLogin();
      long size = Math.round(file.getSize());

      initFile(fileNode,
               id,
               name,
               type,
               link,
               embedLink, //
               thumbnailLink, // downloadLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified,
               size);
      boolean changed = initBoxItem(fileNode, file);

      return new JCRLocalCloudFile(fileNode.getPath(),
                                   id,
                                   name,
                                   link,
                                   previewLink(null, fileNode),
                                   thumbnailLink,
                                   type,
                                   null,
                                   modifiedBy,
                                   createdBy,
                                   created,
                                   modified,
                                   size,
                                   fileNode,
                                   changed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile updateFolder(Node folderNode, Calendar modified) throws CloudDriveException, RepositoryException {

      // Update existing folder metadata and parent (location).
      BoxFolder.Info folder = api.updateFolder(getParentId(folderNode), getId(folderNode), getTitle(folderNode));

      String id = folder.getID();
      String name = folder.getName();
      String link = api.getLink(folder);
      String type = BoxAPI.FOLDER_TYPE;
      String createdBy = folder.getCreatedBy().getLogin();
      Calendar created = Calendar.getInstance();
      created.setTime(folder.getCreatedAt());
      modified = Calendar.getInstance();
      modified.setTime(folder.getModifiedAt());
      String modifiedBy = folder.getModifiedBy().getLogin();

      initFolder(folderNode,
                 id,
                 name,
                 type, //
                 link, // link
                 createdBy, // author
                 modifiedBy, // lastUser
                 created,
                 modified);
      boolean changed = initBoxItem(folderNode, folder);

      return new JCRLocalCloudFile(folderNode.getPath(),
                                   id,
                                   name,
                                   link,
                                   type,
                                   modifiedBy,
                                   createdBy,
                                   created,
                                   modified,
                                   folderNode,
                                   changed);
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
      BoxFile.Info file = api.updateFileContent(getId(fileNode), modified, content);

      String id = file.getID();
      String name = file.getName();
      String type = findMimetype(name);
      String link = api.getLink(file);
      String embedLink = api.getEmbedLink(file);
      String thumbnailLink = api.getThumbnailLink(file);
      String createdBy = file.getCreatedBy().getLogin();
      Calendar created = Calendar.getInstance();
      created.setTime(file.getCreatedAt());
      modified = Calendar.getInstance();
      modified.setTime(file.getModifiedAt());
      String modifiedBy = file.getModifiedBy().getLogin();
      long size = Math.round(file.getSize());

      initFile(fileNode,
               id,
               name,
               type,
               link,
               embedLink, //
               thumbnailLink, // downloadLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified,
               size);
      initBoxItem(fileNode, file);

      return new JCRLocalCloudFile(fileNode.getPath(),
                                   id,
                                   name,
                                   link,
                                   previewLink(null, fileNode),
                                   thumbnailLink,
                                   type,
                                   null,
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
      BoxFile.Info file = api.copyFile(getId(srcFileNode), getParentId(destFileNode), getTitle(destFileNode));

      String id = file.getID();
      String name = file.getName();
      String type = findMimetype(name);
      String link = api.getLink(file);
      String embedLink = api.getEmbedLink(file);
      String thumbnailLink = api.getThumbnailLink(file);
      String createdBy = file.getCreatedBy().getLogin();
      String modifiedBy = file.getModifiedBy().getLogin();
      Calendar created = Calendar.getInstance();
      created.setTime(file.getCreatedAt());
      Calendar modified = Calendar.getInstance();
      modified.setTime(file.getModifiedAt());
      long size = Math.round(file.getSize());

      initFile(destFileNode,
               id,
               name,
               type,
               link,
               embedLink, //
               thumbnailLink, // thumbnailLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified,
               size);
      initBoxItem(destFileNode, file);

      return new JCRLocalCloudFile(destFileNode.getPath(),
                                   id,
                                   name,
                                   link,
                                   previewLink(null, destFileNode),
                                   thumbnailLink,
                                   type,
                                   null,
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
      BoxFolder.Info folder = api.copyFolder(getId(srcFolderNode), getParentId(destFolderNode), getTitle(destFolderNode));

      String id = folder.getID();
      String name = folder.getName();
      String type = BoxAPI.FOLDER_TYPE;
      String link = api.getLink(folder);
      String createdBy = folder.getCreatedBy().getLogin();
      String modifiedBy = folder.getModifiedBy().getLogin();
      Calendar created = Calendar.getInstance();
      created.setTime(folder.getCreatedAt());
      Calendar modified = Calendar.getInstance();
      modified.setTime(folder.getModifiedAt());

      initFolder(destFolderNode,
                 id,
                 name,
                 type, //
                 link, // link
                 createdBy, // author
                 modifiedBy, // lastUser
                 created,
                 modified);
      initBoxItem(destFolderNode, folder);

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
      api.deleteFile(id);
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
      BoxFile.Info trashed = api.trashFile(id);
      return trashed.getItemStatus().equals(BoxAPI.BOX_ITEM_STATE_TRASHED);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean trashFolder(String id) throws CloudDriveException, RepositoryException {
      BoxFolder.Info trashed = api.trashFolder(id);
      return trashed.getItemStatus().equals(BoxAPI.BOX_ITEM_STATE_TRASHED);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile untrashFile(Node fileNode) throws CloudDriveException, RepositoryException {
      String id = fileAPI.getId(fileNode);
      String name = fileAPI.getTitle(fileNode);
      BoxFile.Info file = api.untrashFile(id, name);
      if (!file.getItemStatus().equals(BoxAPI.BOX_ITEM_STATE_TRASHED)) {
        id = file.getID();
        name = file.getName();
        String type = findMimetype(name);
        String link = api.getLink(file);
        String embedLink = api.getEmbedLink(file);
        String thumbnailLink = api.getThumbnailLink(file);
        String createdBy = file.getCreatedBy().getLogin();
        Calendar created = Calendar.getInstance();
        created.setTime(file.getCreatedAt());
        Calendar modified = Calendar.getInstance();
        modified.setTime(file.getModifiedAt());
        String modifiedBy = file.getModifiedBy().getLogin();
        long size = Math.round(file.getSize());

        initFile(fileNode,
                 id,
                 name,
                 type,
                 link,
                 embedLink, //
                 thumbnailLink, // downloadLink
                 createdBy, // author
                 modifiedBy, // lastUser
                 created,
                 modified,
                 size);
        initBoxItem(fileNode, file);

        return new JCRLocalCloudFile(fileNode.getPath(),
                                     id,
                                     name,
                                     link,
                                     previewLink(null, fileNode),
                                     thumbnailLink,
                                     type,
                                     null,
                                     modifiedBy,
                                     createdBy,
                                     created,
                                     modified,
                                     size,
                                     fileNode,
                                     true);
      } // otherwise file wasn't untrashed
      throw new ConstraintException("File cannot be restored from Trash " + name + " (" + id + ")");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile untrashFolder(Node folderNode) throws CloudDriveException, RepositoryException {
      String id = fileAPI.getId(folderNode);
      String name = fileAPI.getTitle(folderNode);
      BoxFolder.Info folder = api.untrashFolder(id, name);
      if (!folder.getItemStatus().equals(BoxAPI.BOX_ITEM_STATE_TRASHED)) {
        id = folder.getID();
        name = folder.getName();
        String link = api.getLink(folder);
        String type = BoxAPI.FOLDER_TYPE;
        String createdBy = folder.getCreatedBy().getLogin();
        Calendar created = Calendar.getInstance();
        created.setTime(folder.getCreatedAt());
        Calendar modified = Calendar.getInstance();
        modified.setTime(folder.getModifiedAt());
        String modifiedBy = folder.getModifiedBy().getLogin();

        initFolder(folderNode,
                   id,
                   name,
                   type, //
                   link, // link
                   createdBy, // author
                   modifiedBy, // lastUser
                   created,
                   modified);
        initBoxItem(folderNode, folder);

        return new JCRLocalCloudFile(folderNode.getPath(),
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
      } // otherwise folder wasn't untrashed
      throw new ConstraintException("Folder cannot be restored from Trash " + name + " (" + id + ")");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTrashSupported() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CloudFile restore(String id, String path) throws NotFoundException, CloudDriveException, RepositoryException {
      throw new SyncNotSupportedException("Restore not supported");
    }
  }

  /**
   * Sync algorithm for Box drive based on drive changes obtained from Events service
   * http://developers.box.com/docs/#events.
   * 
   */
  protected class EventsSync extends SyncCommand {

    /**
     * Box API.
     */
    protected final BoxAPI                         api;

    /**
     * Currently applied history of the drive storage.
     */
    protected final Set<String>                    history        = new LinkedHashSet<String>();

    /**
     * New history with currently applied event ids.
     */
    protected final Set<String>                    newHistory     = new LinkedHashSet<String>();

    /**
     * Queue of events postponed due to not existing parent or source.
     */
    protected final LinkedList<BoxEvent>           postponed      = new LinkedList<BoxEvent>();

    /**
     * Applied items in latest state mapped by item id.
     */
    protected final Map<String, JCRLocalCloudFile> applied        = new LinkedHashMap<String, JCRLocalCloudFile>();

    /**
     * Undeleted events by item id.
     */
    protected final Map<String, BoxItem.Info>      undeleted      = new LinkedHashMap<String, BoxItem.Info>();

    /**
     * Ids of removed items.
     */
    protected final Set<String>                    removedIds     = new LinkedHashSet<String>();

    /**
     * Events from Box to apply.
     */
    protected EventsIterator                       events;

    /** The next event. */
    protected BoxEvent                             nextEvent;

    /** The last postponed. */
    protected BoxEvent                             lastPostponed;

    /** The postponed number. */
    protected int                                  prevPostponedNumber, postponedNumber;

    /**
     * Counter of applied events in this Sync. Used for multi-pass looping over the events.
     */
    protected int                                  appliedCounter = 0;

    /**
     * Counter of events read from Box service. Used for multi-pass looping over the events.
     */
    protected int                                  readCounter    = 0;

    /** The saved stream position. */
    protected Long                                 savedStreamPosition;

    /** The local stream position. */
    protected Long                                 localStreamPosition;

    /**
     * Create command for Box synchronization.
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
      localStreamPosition = getChangeId();
      savedStreamPosition = null;

      // buffer all items,
      // apply them in proper order (taking in account parent existence),
      // remove already applied (check by event id in history),
      // apply others to local nodes
      // save just applied events as history
      events = api.getEvents(localStreamPosition);
      iterators.add(events);

      // Local history, it contains something applied in previous sync, it can be empty if it was full sync.
      for (String es : driveNode.getProperty("box:streamHistory").getString().split(";")) {
        history.add(es);
      }

      // FYI Box API tells about Events service:
      // Events will occasionally arrive out of order. For example a file-upload might show up
      // before the Folder-create event. You may need to buffer events and apply them in a logical
      // order.
      while (hasNextEvent()) {
        BoxEvent event = nextEvent();
        BoxResource.Info source = event.getSourceInfo();
        if (source instanceof BoxItem.Info) {
          BoxItem.Info item = (BoxItem.Info) source;
          BoxEvent.Type eventType = event.getType();

          String id = item.getID();
          String name = item.getName();
          String sequenceId = item.getSequenceID();

          if (LOG.isDebugEnabled()) {
            LOG.debug("> " + eventType + ": " + id + " " + name + " " + sequenceId);
          }

          // find parent id
          String parentId;
          if (eventType.equals(BoxEvent.Type.ITEM_TRASH)) {
            if (hasRemoved(id)) {
              // Handle removed locally and returned from cloud side
              if (LOG.isDebugEnabled()) {
                LOG.debug(">> Returned file removal " + id + " " + name);
              }
              cleanRemoved(id);
              continue; // this item was removed locally
            }

            BoxFolder.Info itemParent = item.getParent();
            if (itemParent != null) {
              parentId = itemParent.getID();
            } else {
              // it is a child file of already deleted/trashed folder, need wait for a parent removal,
              remove(id, null);
              continue;
            }
          } else {
            BoxFolder.Info itemParent = item.getParent();
            if (itemParent != null) {
              parentId = itemParent.getID();
            } else {
              // this user cannot access the parent of this item, as we already skipped trashed above,
              // it is something what we don't expect here, postpone and break the cycle: need run full sync.
              // XXX Aug 24 2015, after upgrade to SDK 1.1.0 it's possible ITEM_UNDELETE_VIA_TRASH event will
              // appear with item status "deleted" (result of folder removed locally then restored in Box,
              // prior this it was a file removal in the folder and it appears mixed with the other files in
              // it during the restore)
              postpone(event);
              break;
            }
          }

          // find parent node
          Node parent;
          if (BoxAPI.BOX_ROOT_ID.equals(parentId)) {
            parent = driveNode;
          } else {
            JCRLocalCloudFile local = applied(parentId);
            if (local != null) {
              parent = local.getNode();
              // XXX workaround bug in JCR, otherwise it may load previously deleted node from persistence
              // what will lead to NPE later
              parent.getParent().getNodes();
            } else {
              parent = findNode(parentId); // can be null
              if (parent == null) {
                // parent not (yet) found or was removed, we postpone the event and wait for it in the order
                // and fail at the end if will be not applied.
                // FYI special logic for child removal of already removed parent, JCR removes all together
                // with the parent - we skip such events.
                if (!(removedIds.contains(parentId) && eventType.equals(BoxEvent.Type.ITEM_TRASH))) {
                  postpone(event);
                } // else parent node not found and it is already removed: we skip it.
                continue;
              }
            }
          }

          try {
            if (eventType.equals(BoxEvent.Type.ITEM_CREATE) || eventType.equals(BoxEvent.Type.ITEM_UPLOAD)) {
              if (hasUpdated(id)) {
                // this item was created/modified locally
                if (LOG.isDebugEnabled()) {
                  LOG.debug(">> Returned file creation/modification " + id + " " + name);
                }
                cleanUpdated(id);
              } else {
                if (LOG.isDebugEnabled()) {
                  LOG.debug(">> File create/modify " + id + " " + name);
                }
                apply(updateItem(api, item, parent, null));
              }
            } else if (eventType.equals(BoxEvent.Type.ITEM_MOVE) || eventType.equals(BoxEvent.Type.ITEM_RENAME)) {
              if (hasUpdated(id)) {
                // this item was moved/renamed locally
                if (LOG.isDebugEnabled()) {
                  LOG.debug(">> Returned file move/rename " + id + " " + name);
                }
                cleanUpdated(id);
              } else {
                BoxItem.Info undelete = undeleted(id);
                if (undelete != null) {
                  // apply undelete here if ITEM_MOVE appeared after ITEM_UNDELETE_VIA_TRASH,
                  // it's not JCR item move actually - we just add a new node using name from this event
                  if (LOG.isDebugEnabled()) {
                    LOG.debug(">> File undeleted " + id + " " + name);
                  }
                  apply(updateItem(api, item, parent, null));
                } else {
                  // move node
                  Node sourceNode;
                  JCRLocalCloudFile local = applied(id);
                  if (local != null) {
                    // using transient change from this events order for this item,
                    sourceNode = local.getNode();
                  } else {
                    // try search in persisted storage by file id
                    sourceNode = findNode(id);
                  }
                  if (sourceNode != null) {
                    if (fileAPI.getTitle(sourceNode).equals(name) && fileAPI.getParentId(sourceNode).equals(parentId)) {
                      // file node already has required name and parent
                      // apply(updateItem(api, item, parent, sourceNode)); // no need to update it
                    } else {
                      if (LOG.isDebugEnabled()) {
                        LOG.debug(">> File move/rename " + id + " " + name);
                      }
                      // XXX workaround bug in JCR (see also above in this method), otherwise it may load
                      // previously moved node from persistence what will lead to PathNotFoundException (on
                      // move/rename) in moveFile()
                      parent.getNodes();

                      Node destNode = moveFile(id, name, sourceNode, parent);
                      apply(updateItem(api, item, parent, destNode));
                    }
                  } else {
                    // else, wait for appearance of source node in following events,
                    // here we also covering ITEM_MOVE as part of undeleted item events if the move
                    // appeared first.
                    postpone(event);
                  }
                }
              }
            } else if (eventType.equals(BoxEvent.Type.ITEM_TRASH)) {
              // FYI removed locally checked above in this iteration
              Node node = readNode(parent, name, id);
              if (node != null) {
                if (LOG.isDebugEnabled()) {
                  LOG.debug(">> File removal " + id + " " + name);
                }
                String path = node.getPath();
                // remove file links outside the drive, then the node itself
                removeNode(node);
                remove(id, path);
              } else {
                // wait for a target node appearance in following events
                postpone(event);
              }
            } else if (eventType.equals(BoxEvent.Type.ITEM_UNDELETE_VIA_TRASH)) {
              if (hasUpdated(id)) {
                // this item was untrashed locally
                if (LOG.isDebugEnabled()) {
                  LOG.debug(">> Returned file untrash " + id + " " + name);
                }
                cleanUpdated(id);
              } else {
                // undeleted folder will appear with its files, but in undefined order!
                // for undeleted item we also will have ITEM_MOVE to a final restored destination
                // here we already have a parent node, but ensure we have a "place" for undeleted item
                Node place = readNode(parent, name, id);
                if (place == null) {
                  if (LOG.isDebugEnabled()) {
                    LOG.debug(">> File untrash " + id + " " + name);
                  }
                  apply(updateItem(api, item, parent, null));
                } else if (fileAPI.getTitle(place).equals(name) && fileAPI.getParentId(place).equals(parentId)) {
                  // this file already exists in the drive, may be it is a child of untrashed
                } else {
                  // another item already there, wait for ITEM_MOVE with actual "place" for the item
                  // XXX ITEM_MOVE not observed in Box's move changes at Apr 27 2014
                  undelete(item);
                  postpone(event);
                }
              }
            } else if (eventType.equals(BoxEvent.Type.ITEM_COPY)) {
              if (hasUpdated(id)) {
                // this item was copied locally
                if (LOG.isDebugEnabled()) {
                  LOG.debug(">> Returned file copy " + id + " " + name);
                }
                cleanUpdated(id);
              } else {
                if (LOG.isDebugEnabled()) {
                  LOG.debug(">> File copy " + id + " " + name);
                }
                JCRLocalCloudFile local = applied(id);
                if (local != null) {
                  // using transient change from this events order for this item
                  Node sourceNode = local.getNode();
                  if (fileAPI.getTitle(sourceNode).equals(name) && fileAPI.getParentId(sourceNode).equals(parentId)) {
                    // file node already has required name and parent, may be it is a child of copied
                  } else {
                    Node destNode = copyFile(sourceNode, parent);
                    apply(updateItem(api, item, parent, destNode));
                  }
                } else {
                  // read the only copied node from the Box
                  local = updateItem(api, item, parent, null);
                  if (local.isFolder()) {
                    // and fetch child files
                    try {
                      fetchChilds(local.getId(), local.getNode());
                      apply(local);
                    } catch (NotFoundException e) {
                      // this node not found...
                      removeNode(local.getNode());
                      remove(id, local.getPath());
                      if (LOG.isDebugEnabled()) {
                        LOG.debug("Copied node already removed in cloud - remove it locally. " + e.getMessage());
                      }
                    }
                  } else {
                    apply(local);
                  }
                }
              }
            } else {
              LOG.warn("Skipped unexpected change from Box Event: " + eventType);
            }
          } catch (PathNotFoundException e) {
            // here is a possible inconsistency between events flow and actual state due to previous JCR
            // errors (e.g. unexpectedly stopped storage), thus we break, postpone and let full sync fix the
            // drive
            postpone(event);
            break;
          }
        } else {
          LOG.warn("Skipping non Item in Box events: " + source);
        }
      }

      if (hasPostponed()) {
        // EventsSync cannot solve all changes, need run FullSync
        LOG.warn("Not all events applied for Box sync. Running full sync.");

        // rollback everything from this sync
        rollback(driveNode);

        // we need full sync in this case
        FullSync fullSync = new FullSync();
        fullSync.execLocal();

        replaceChanged(fullSync.getFiles());
        replaceRemoved(fullSync.getRemoved());
      } else {
        // save history
        // TODO consider for saving the history of several hours or even a day
        StringBuilder newHistoryData = new StringBuilder();
        for (Iterator<String> eriter = newHistory.iterator(); eriter.hasNext();) {
          newHistoryData.append(eriter.next());
          if (eriter.hasNext()) {
            newHistoryData.append(';');
          }
        }
        driveNode.setProperty("box:streamHistory", newHistoryData.toString());

        // update sync position
        setChangeId(events.getNextStreamPosition());
      }
    }

    /**
     * {@inheritDoc}
     */
    protected void preSaveChunk() throws CloudDriveException, RepositoryException {
      long streamPosition = events.getNextStreamPosition();
      if (streamPosition > localStreamPosition && (savedStreamPosition == null || streamPosition > savedStreamPosition)) {
        // if chunk will be saved then also save the stream position of last applied event in the drive
        setChangeId(streamPosition);
        savedStreamPosition = streamPosition;
      }
    }

    /**
     * Fetch childs.
     *
     * @param fileId the file id
     * @param parent the parent
     * @return the box folder
     * @throws CloudDriveException the cloud drive exception
     * @throws RepositoryException the repository exception
     */
    protected BoxFolder fetchChilds(String fileId, Node parent) throws CloudDriveException, RepositoryException {
      ItemsIterator items = api.getFolderItems(fileId);
      iterators.add(items);
      while (items.hasNext()) {
        BoxItem.Info item = items.next();
        JCRLocalCloudFile localItem = updateItem(api, item, parent, null);
        apply(localItem);
        if (localItem.isFolder()) {
          // go recursive to the folder
          fetchChilds(localItem.getId(), localItem.getNode());
        }
      }
      return items.parent;
    }

    /**
     * Read event.
     *
     * @return the box event
     * @throws CloudDriveException the cloud drive exception
     */
    protected BoxEvent readEvent() throws CloudDriveException {
      while (events.hasNext()) {
        BoxEvent next = events.next();

        // keep in new history all we get from the Box API, it can be received in next sync also
        newHistory.add(next.getID());

        if (!history.contains(next.getID())) {
          readCounter++;
          return next;
        }
      }
      return null;
    }

    /**
     * Checks for next event.
     *
     * @return true, if successful
     * @throws CloudDriveException the cloud drive exception
     */
    protected boolean hasNextEvent() throws CloudDriveException {
      // condition of next: if Box events not yet full read or we have postponed and their number decreases
      // from cycle to cycle over the whole queue.
      if (nextEvent != null) {
        return true;
      }
      nextEvent = readEvent();
      if (nextEvent != null) {
        return true;
      }
      // return postponed once more only if more than one event was read, otherwise postponed has no sense
      return readCounter > 1 && postponed.size() > 0 && (lastPostponed != null ? prevPostponedNumber > postponedNumber : true);
    }

    /**
     * Next event.
     *
     * @return the box event
     * @throws NoSuchElementException the no such element exception
     * @throws CloudDriveException the cloud drive exception
     */
    protected BoxEvent nextEvent() throws NoSuchElementException, CloudDriveException {
      BoxEvent event = null;
      if (nextEvent != null) {
        event = nextEvent;
        nextEvent = null;
      } else {
        event = readEvent();
      }

      if (event != null) {
        return event;
      }

      if (postponed.size() > 0) {
        if (lastPostponed == null) {
          lastPostponed = postponed.getLast(); // init marker of postponed queue end
          postponedNumber = readCounter - appliedCounter; // init number of postponed
          prevPostponedNumber = Integer.MAX_VALUE; // need this for hasNextEvent() logic
        }

        BoxEvent firstPostponed = postponed.poll();
        // we store number of postponed on each iteration over the postponed queue, if number doesn't go down
        // then we cannot apply other postponed we have and need run FullSync.
        if (firstPostponed == lastPostponed) {
          prevPostponedNumber = postponedNumber;
          postponedNumber = readCounter - appliedCounter; // next number of postponed
        }
        return firstPostponed;
      }

      throw new NoSuchElementException("No more events.");
    }

    /**
     * Postpone.
     *
     * @param event the event
     */
    protected void postpone(BoxEvent event) {
      postponed.add(event);
    }

    /**
     * Checks for postponed.
     *
     * @return true, if successful
     */
    protected boolean hasPostponed() {
      if (postponed.size() > 0) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Not resolved Box events >>>> ");
          for (BoxEvent e : postponed) {
            BoxResource.Info source = e.getSourceInfo();
            if (source instanceof BoxItem.Info) {
              BoxItem.Info item = (BoxItem.Info) source;
              LOG.debug(e.getType() + ": " + item.getID() + " " + item.getName() + " " + item.getSequenceID());
            }
          }
          LOG.debug("<<<<");
        }
      }
      return postponed.size() > 0;
    }

    /**
     * Undeleted.
     *
     * @param itemId the item id
     * @return the box item. info
     */
    protected BoxItem.Info undeleted(String itemId) {
      return undeleted.get(itemId);
    }

    /**
     * Undelete.
     *
     * @param item the item
     */
    protected void undelete(BoxItem.Info item) {
      undeleted.put(item.getID(), item);
    }

    /**
     * Apply.
     *
     * @param local the local
     * @throws RepositoryException the repository exception
     * @throws CloudDriveException the cloud drive exception
     */
    protected void apply(JCRLocalCloudFile local) throws RepositoryException, CloudDriveException {
      if (local.isChanged()) {
        applied.put(local.getId(), local);
        removeRemoved(local.getPath());
        removedIds.remove(local.getId());
        addChanged(local);
        appliedCounter++;
      }
    }

    /**
     * Applied.
     *
     * @param itemId the item id
     * @return the JCR local cloud file
     */
    protected JCRLocalCloudFile applied(String itemId) {
      return applied.get(itemId);
    }

    /**
     * Removes the.
     *
     * @param itemId the item id
     * @param itemPath the item path
     * @return the JCR local cloud file
     * @throws RepositoryException the repository exception
     * @throws CloudDriveException the cloud drive exception
     */
    protected JCRLocalCloudFile remove(String itemId, String itemPath) throws RepositoryException, CloudDriveException {
      appliedCounter++;
      if (itemPath != null) {
        addRemoved(itemPath);
      }
      removedIds.add(itemId);
      return applied.remove(itemId);
    }
  }

  /**
   * The Class BoxState.
   */
  public class BoxState implements FilesState {

    /** The link. */
    final ChangesLink link;

    /**
     * Instantiates a new box state.
     *
     * @param link the link
     */
    protected BoxState(ChangesLink link) {
      this.link = link;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<String> getUpdating() {
      return state.getUpdating();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isUpdating(String fileIdOrPath) {
      return state.isUpdating(fileIdOrPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isNew(String fileIdOrPath) {
      return state.isNew(fileIdOrPath);
    }

    /**
     * Gets the type.
     *
     * @return the type
     */
    public String getType() {
      return link.getType();
    }

    /**
     * Gets the url.
     *
     * @return the url
     */
    public String getUrl() {
      return link.getUrl();
    }

    /**
     * Gets the ttl.
     *
     * @return the ttl
     */
    public long getTtl() {
      return link.getTtl();
    }

    /**
     * Gets the max retries.
     *
     * @return the maxRetries
     */
    public long getMaxRetries() {
      return link.getMaxRetries();
    }

    /**
     * Gets the retry timeout.
     *
     * @return the retryTimeout
     */
    public long getRetryTimeout() {
      return link.getRetryTimeout();
    }

    /**
     * Gets the outdated timeout.
     *
     * @return the outdatedTimeout
     */
    public long getOutdatedTimeout() {
      return link.getOutdatedTimeout();
    }

    /**
     * Gets the created.
     *
     * @return the created
     */
    public long getCreated() {
      return link.getCreated();
    }

    /**
     * Checks if is outdated.
     *
     * @return true, if is outdated
     */
    public boolean isOutdated() {
      return link.isOutdated();
    }
  }

  /**
   * Instantiates a new JCR local box drive.
   *
   * @param user the user
   * @param driveNode the drive node
   * @param sessionProviders the session providers
   * @param finder the finder
   * @param mimeTypes the mime types
   * @throws CloudDriveException the cloud drive exception
   * @throws RepositoryException the repository exception
   */
  protected JCRLocalBoxDrive(BoxUser user,
                             Node driveNode,
                             SessionProviderService sessionProviders,
                             NodeFinder finder,
                             ExtendedMimeTypeResolver mimeTypes) throws CloudDriveException, RepositoryException {
    super(user, driveNode, sessionProviders, finder, mimeTypes);
    getUser().api().getToken().addListener(this);
  }

  /**
   * Instantiates a new JCR local box drive.
   *
   * @param apiBuilder the api builder
   * @param provider the provider
   * @param driveNode the drive node
   * @param sessionProviders the session providers
   * @param finder the finder
   * @param mimeTypes the mime types
   * @throws RepositoryException the repository exception
   * @throws CloudDriveException the cloud drive exception
   */
  protected JCRLocalBoxDrive(API apiBuilder,
                             BoxProvider provider,
                             Node driveNode,
                             SessionProviderService sessionProviders,
                             NodeFinder finder,
                             ExtendedMimeTypeResolver mimeTypes) throws RepositoryException, CloudDriveException {
    super(loadUser(apiBuilder, provider, driveNode), driveNode, sessionProviders, finder, mimeTypes);
    getUser().api().getToken().addListener(this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void initDrive(Node driveNode) throws CloudDriveException, RepositoryException {
    super.initDrive(driveNode);

    driveNode.setProperty("ecd:id", BoxAPI.BOX_ROOT_ID);
  }

  /**
   * Load user from the drive Node.
   *
   * @param apiBuilder {@link API} API builder
   * @param provider {@link BoxProvider}
   * @param driveNode {@link Node} root of the drive
   * @return {@link BoxUser}
   * @throws RepositoryException the repository exception
   * @throws BoxException the box exception
   * @throws CloudDriveException the cloud drive exception
   */
  protected static BoxUser loadUser(API apiBuilder, BoxProvider provider, Node driveNode) throws RepositoryException,
                                                                                          BoxException,
                                                                                          CloudDriveException {
    String username = driveNode.getProperty("ecd:cloudUserName").getString();
    String email = driveNode.getProperty("ecd:userEmail").getString();
    String userId = driveNode.getProperty("ecd:cloudUserId").getString();

    String accessToken = driveNode.getProperty("box:oauth2AccessToken").getString();
    String refreshToken;
    try {
      refreshToken = driveNode.getProperty("box:oauth2RefreshToken").getString();
    } catch (PathNotFoundException e) {
      refreshToken = null;
    }
    long expirationTime = driveNode.getProperty("box:oauth2TokenExpirationTime").getLong();

    BoxAPI driveAPI = apiBuilder.load(refreshToken, accessToken, expirationTime).build();

    return new BoxUser(userId, username, email, provider, driveAPI);
  }

  /**
   * {@inheritDoc}
   * 
   */
  @Override
  public void onUserTokenRefresh(UserToken token) throws CloudDriveException {
    try {
      jcrListener.disable();
      Node driveNode = rootNode();
      try {
        driveNode.setProperty("box:oauth2AccessToken", token.getAccessToken());
        driveNode.setProperty("box:oauth2RefreshToken", token.getRefreshToken());
        driveNode.setProperty("box:oauth2TokenExpirationTime", token.getExpirationTime());

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
        if (driveNode.hasProperty("box:oauth2AccessToken")) {
          driveNode.getProperty("box:oauth2AccessToken").remove();          
        }
        if (driveNode.hasProperty("box:oauth2RefreshToken")) {
          driveNode.getProperty("box:oauth2RefreshToken").remove();          
        }
        if (driveNode.hasProperty("box:oauth2TokenExpirationTime")) {
          driveNode.getProperty("box:oauth2TokenExpirationTime").remove();          
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
  protected ConnectCommand getConnectCommand() throws DriveRemovedException, RepositoryException {
    return new Connect();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected SyncCommand getSyncCommand() throws DriveRemovedException, SyncNotSupportedException, RepositoryException {

    Calendar now = Calendar.getInstance();
    Calendar last = rootNode().getProperty("box:streamDate").getDate();

    // FYI we force a full sync (a whole drive traversing) each defined period.
    // We do this for a case when Box will not provide a full history for files connected long time ago and
    // weren't synced day by day (Box drive was rarely used).
    // Their doc tells: Box does not store all events for all time on your account. We store somewhere between
    // 2 weeks and 2 months of events.
    if (now.getTimeInMillis() - last.getTimeInMillis() < FULL_SYNC_PERIOD) {
      return new EventsSync();
    } else {
      return new FullSync();
    }
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
      return rootNode().getProperty("box:streamPosition").getLong();
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
    driveNode.setProperty("box:streamPosition", id);
    driveNode.setProperty("box:streamDate", Calendar.getInstance());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BoxUser getUser() {
    return (BoxUser) user;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BoxState getState() throws DriveRemovedException,
                             CloudProviderException,
                             RepositoryException,
                             RefreshAccessException {
    return new BoxState(getUser().api().getChangesLink());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void refreshAccess() throws CloudDriveException {
    // Not used. Box API does this internally and fires UserTokenRefreshListener.
    // See UserTokenRefreshListener implementation in this local drive.
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void updateAccess(CloudUser newUser) throws CloudDriveException, RepositoryException {
    getUser().api().updateToken(((BoxUser) newUser).api().getToken());
  }

  /**
   * Initialize Box's common specifics of files and folders.
   *
   * @param localNode {@link Node}
   * @param item {@link BoxItem.Info}
   * @return boolean <code>true</code> if Box file was changed comparing to previous state, <code>false</code>
   * @throws RepositoryException the repository exception
   * @throws BoxException the box exception
   */
  protected boolean initBoxItem(Node localNode, BoxItem.Info item) throws RepositoryException, BoxException {
    boolean changed = false;

    // Etag and sequence_id used for synchronization
    localNode.setProperty("box:etag", item.getEtag());
    try {
      String sequenceIdStr = item.getSequenceID();
      if (sequenceIdStr != null) {
        if (LOG.isDebugEnabled()) {
          LOG.debug(">>> initBoxItem: " + localNode.getPath() + " = " + item.getID() + " " + item.getName() + " "
              + item.getSequenceID());
        }
        Long prevSequenceId;
        try {
          prevSequenceId = localNode.getProperty("box:sequenceId").getLong();
        } catch (PathNotFoundException e) {
          prevSequenceId = null;
        }
        long sequenceId = Long.parseLong(sequenceIdStr);
        localNode.setProperty("box:sequenceId", sequenceId);
        changed = prevSequenceId != null ? sequenceId != prevSequenceId.longValue() : true;
      } // else, it's null (root or trash)
    } catch (NumberFormatException e) {
      throw new BoxException("Error parsing sequence_id of " + localNode.getPath(), e);
    }

    // properties below not actually used by the Cloud Drive,
    // they are just for information available to PLF user
    localNode.setProperty("box:ownedBy", item.getOwnedBy().getLogin());
    localNode.setProperty("box:description", item.getDescription());

    BoxSharedLink shared = item.getSharedLink();
    if (shared != null) {
      localNode.setProperty("box:sharedAccess", shared.getAccess().toString());
      localNode.setProperty("box:sharedCanDownload", shared.getPermissions().getCanDownload());
    }

    return changed;
  }

  /**
   * Find mimetype.
   *
   * @param fileName the file name
   * @return the string
   */
  protected String findMimetype(String fileName) {
    String name = fileName.toUpperCase().toLowerCase();
    String ext = name.substring(name.lastIndexOf(".") + 1);
    if (ext.equals(BoxAPI.BOX_WEBDOCUMENT_EXT)) {
      return BoxAPI.BOX_WEBDOCUMENT_MIMETYPE;
    } else if (ext.equals(BoxAPI.BOX_NOTE_EXT)) {
      return BoxAPI.BOX_NOTE_MIMETYPE;
    } else {
      return mimeTypes.getMimeType(fileName);
    }
  }

  /**
   * Update or create a local node of Cloud File. If the node is <code>null</code> then it will be open on the
   * given parent and created if not already exists.
   * 
   * @param api {@link BoxAPI}
   * @param item {@link BoxItem.Info}
   * @param parent {@link Node}
   * @param node {@link Node}, can be <code>null</code>
   * @return {@link JCRLocalCloudFile}
   * @throws RepositoryException for storage errors
   * @throws CloudDriveException for drive or format errors
   */
  protected JCRLocalCloudFile updateItem(BoxAPI api, BoxItem.Info item, Node parent, Node node) throws RepositoryException,
                                                                                                CloudDriveException {
    String id = item.getID();
    String name = item.getName();
    boolean isFolder = item instanceof BoxFolder.Info;
    String type = isFolder ? BoxAPI.FOLDER_TYPE : findMimetype(name);

    long sequenceId = getSequenceId(item);

    // read/create local node if not given
    if (node == null) {
      if (isFolder) {
        node = openFolder(id, name, parent);
      } else {
        node = openFile(id, name, parent);
      }
    }

    boolean changed = node.isNew() || (sequenceId >= 0 && node.getProperty("box:sequenceId").getLong() < sequenceId)
        || !node.getProperty("box:etag").getString().equals(item.getEtag());

    Calendar created = Calendar.getInstance();
    created.setTime(item.getCreatedAt());
    Calendar modified = Calendar.getInstance();
    modified.setTime(item.getModifiedAt());
    String createdBy = item.getCreatedBy().getLogin();
    String modifiedBy;
    com.box.sdk.BoxUser.Info modifier = item.getModifiedBy();
    if (modifier != null) {
      modifiedBy = modifier.getLogin();
    } else {
      modifiedBy = createdBy; // XXX when item shared by other user it may not have a modifier - use the
                              // creator then
    }

    String link, embedLink, thumbnailLink;
    JCRLocalCloudFile file;
    if (isFolder) {
      link = embedLink = api.getLink(item);
      thumbnailLink = api.getThumbnailLink(item);
      if (changed) {
        initFolder(node,
                   id,
                   name,
                   type, // type=folder
                   link,
                   createdBy,
                   modifiedBy,
                   created,
                   modified);
        initBoxItem(node, item);
      }
      file = new JCRLocalCloudFile(node.getPath(),
                                   id,
                                   name,
                                   link,
                                   type,
                                   modifiedBy,
                                   createdBy,
                                   created,
                                   modified,
                                   node,
                                   changed); // Sep 29, 2015 was true
    } else {
      // TODO for thumbnail we can use Thumbnail service
      // https://api.box.com/2.0/files/FILE_ID/thumbnail.png?min_height=256&min_width=256
      link = api.getLink(item);
      embedLink = api.getEmbedLink(item);
      thumbnailLink = api.getThumbnailLink(item);
      long size = Math.round(item.getSize());
      if (changed) {
        initFile(node,
                 id,
                 name,
                 type, // mimetype
                 link,
                 embedLink,
                 thumbnailLink,
                 createdBy,
                 modifiedBy,
                 created,
                 modified,
                 size);
        initBoxItem(node, item);
      }
      file = new JCRLocalCloudFile(node.getPath(),
                                   id,
                                   name,
                                   link,
                                   embedLink,
                                   thumbnailLink,
                                   type,
                                   null,
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
   * Not in range.
   *
   * @param path the path
   * @param range the range
   * @return true, if successful
   */
  protected boolean notInRange(String path, Collection<String> range) {
    for (String p : range) {
      if (path.startsWith(p)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Gets the sequence id.
   *
   * @param item the item
   * @return the sequence id
   * @throws BoxFormatException the box format exception
   */
  protected Long getSequenceId(BoxItem.Info item) throws BoxFormatException {
    // TODO do we need use Etag in conjunction with sequence_id? they mean almost the same in Box API.
    try {
      String sequenceIdStr = item.getSequenceID();
      if (sequenceIdStr != null) {
        return Long.parseLong(sequenceIdStr);
      } else {
        return -1l; // for null (root or trash)
      }
    } catch (NumberFormatException e) {
      throw new BoxFormatException("Error parsing sequence_id of " + item.getID() + " " + item.getName(), e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String previewLink(String type, Node fileNode) throws RepositoryException {
    BoxUser user = getUser();
    String link = super.previewLink(type, fileNode);
    if (link != null && user.getProvider().isLoginSSO() && user.getEnterpriseId() != null) {
      // append encoded link to access URL
      try {
        link = URLEncoder.encode(link, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        LOG.warn("Cannot encode URL " + fileNode + ":" + e);
      }
      return String.format(BoxAPI.BOX_EMBED_URL_SSO, user.getEnterpriseId(), link);
    }
    return link;
  }
}
