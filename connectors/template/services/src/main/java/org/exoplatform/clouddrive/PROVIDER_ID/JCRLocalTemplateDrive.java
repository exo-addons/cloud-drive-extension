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
package org.exoplatform.clouddrive.PROVIDER_ID;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudFile;
import org.exoplatform.clouddrive.CloudFileAPI;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.ConflictException;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.NotFoundException;
import org.exoplatform.clouddrive.SyncNotSupportedException;
import org.exoplatform.clouddrive.PROVIDER_ID.TemplateAPI.EventsIterator;
import org.exoplatform.clouddrive.PROVIDER_ID.TemplateAPI.ItemsIterator;
import org.exoplatform.clouddrive.PROVIDER_ID.TemplateConnector.API;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudDrive;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudFile;
import org.exoplatform.clouddrive.jcr.NodeFinder;
import org.exoplatform.clouddrive.oauth2.UserToken;
import org.exoplatform.clouddrive.oauth2.UserTokenRefreshListener;
import org.exoplatform.clouddrive.utils.ExtendedMimeTypeResolver;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

import java.io.InputStream;
import java.util.Calendar;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;

/**
 * Local drive for Template provider.<br>
 * 
 * TODO Code below is "almost" dummy and just lets an example how it could be. Fill it with
 * the logic of actual implementation reusing the ideas (or dropping them).
 * 
 */
public class JCRLocalTemplateDrive extends JCRLocalCloudDrive implements UserTokenRefreshListener {

  /**
   * Period to perform {@link FullSync} as a next sync request. See implementation of
   * {@link #getSyncCommand()}.
   */
  public static final long FULL_SYNC_PERIOD = 24 * 60 * 60 * 60 * 1000; // 24hrs

  /**
   * Connect algorithm for Template drive.
   */
  protected class Connect extends ConnectCommand {

    protected final TemplateAPI api;

    protected Connect() throws RepositoryException, DriveRemovedException {
      this.api = getUser().api();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void fetchFiles() throws CloudDriveException, RepositoryException {
      // Obtain connectChangeId before the actual fetch of cloud files,
      // this will provide us a proper connectChangeId to start sync from later.
      long connectChangeId = api.getEvents(-1).getChangeId();

      Object root = fetchChilds("ROOT_ID", rootNode); // TODO use actual ID
      initCloudItem(rootNode, root); // init parent

      // actual drive URL (its root folder's id), see initDrive() also
      rootNode.setProperty("ecd:url", api.getLink(root));

      // sync stream
      setChangeId(connectChangeId);
      rootNode.setProperty("YOUR_PROVIDE_ID:streamHistory", "");
    }

    protected Object fetchChilds(String fileId, Node parent) throws CloudDriveException, RepositoryException {
      ItemsIterator items = api.getFolderItems(fileId);
      iterators.add(items);
      while (items.hasNext()) {
        Object item = items.next();
        JCRLocalCloudFile localItem = updateItem(api, item, parent, null);
        if (localItem.isChanged()) {
          changed.add(localItem);
          if (localItem.isFolder()) {
            // go recursive to the folder
            fetchChilds(localItem.getId(), localItem.getNode());
          }
        } else {
          throw new TemplateException("Fetched item was not added to local drive storage");
        }
      }
      return items.parent;
    }
  }

  /**
   * {@link SyncCommand} of cloud drive based on all remote files traversing: we do
   * compare all remote files with locals by its Etag and fetch an item if the tags differ.
   */
  protected class FullSync extends SyncCommand {

    /**
     * Internal API.
     */
    protected final TemplateAPI api;

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
      // real all local nodes of this drive
      readLocalNodes();

      // remember full sync position (same logic as in Connect command)
      long syncChangeId = api.getEvents(-1).getChangeId();

      // sync with cloud
      Object root = syncChilds("ROOT_ID", rootNode); // TODO use actual ID
      initCloudItem(rootNode, root); // init parent

      // remove local nodes of files not existing remotely, except of root
      nodes.remove("ROOT_ID"); // TODO use actual ID
      for (Iterator<List<Node>> niter = nodes.values().iterator(); niter.hasNext()
          && !Thread.currentThread().isInterrupted();) {
        List<Node> nls = niter.next();
        niter.remove();
        for (Node n : nls) {
          String npath = n.getPath();
          if (notInRange(npath, removed)) {
            removed.add(npath);
            n.remove();
          }
        }
      }

      // update sync position
      setChangeId(syncChangeId);
    }

    protected Object syncChilds(String folderId, Node parent) throws RepositoryException, CloudDriveException {
      ItemsIterator items = api.getFolderItems(folderId);
      iterators.add(items);
      while (items.hasNext() && !Thread.currentThread().isInterrupted()) {
        Object item = items.next();

        // remove from map of local to mark the item as existing
        List<Node> existing = nodes.remove("TODO item.getId()");

        JCRLocalCloudFile localItem = updateItem(api, item, parent, null);
        if (localItem.isChanged()) {
          changed.add(localItem);

          // cleanup of this file located in another place (usecase of rename/move)
          // XXX this also assumes that cloud doesn't support linking of files to other folders
          if (existing != null) {
            for (Iterator<Node> eiter = existing.iterator(); eiter.hasNext();) {
              Node enode = eiter.next();
              String path = localItem.getPath();
              String epath = enode.getPath();
              if (!epath.equals(path) && notInRange(epath, removed)) {
                removed.add(epath);
                enode.remove();
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
      return items.parent;
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
        rootNode.setProperty("ecd:localHistory", empty);
        rootNode.setProperty("ecd:localChanges", empty);
        rootNode.save();
      } catch (Throwable e) {
        LOG.error("Error cleaning local history in " + title(), e);
      } finally {
        jcrListener.enable();
      }
    }
  }

  /**
   * {@link CloudFileAPI} implementation.
   */
  protected class FileAPI extends AbstractFileAPI {

    /**
     * Internal API.
     */
    protected final TemplateAPI api;

    FileAPI() {
      this.api = getUser().api();
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
      Object file;
      try {
        file = api.createFile(parentId, title, created, content);
      } catch (ConflictException e) {
        // XXX we assume name as factor of equality here and make local file to reflect the cloud side
        Object existing = null;
        ItemsIterator files = api.getFolderItems(parentId);
        while (files.hasNext()) {
          Object item = files.next();
          if (title.equals("TODO item.getName()")) { // TODO do more complex check if required
            existing = item;
            break;
          }
        }
        if (existing == null) {
          throw e; // we cannot do anything at this level
        } else {
          file = existing;
          // and erase local file data here
          if (fileNode.hasNode("jcr:content")) {
            fileNode.getNode("jcr:content").setProperty("jcr:data", DUMMY_DATA); // empty data by default
          }
        }
      }

      String id = "TODO"; // file.getId();
      String name = "TODO"; // file.getName();
      String link = api.getLink(file);
      String embedLink = api.getEmbedLink(file);
      String thumbnailLink = link; // TODO need real thumbnail
      String createdBy = "TODO"; // file.getCreatedBy().getLogin();
      String modifiedBy = "TODO"; // file.getModifiedBy().getLogin();
      String type = "TODO"; // file.getType();

      initFile(fileNode, id, name, type, link, embedLink, //
               thumbnailLink, // downloadLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified);
      initCloudItem(fileNode, file);

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
      Object folder;
      try {
        folder = api.createFolder(getParentId(folderNode), getTitle(folderNode), created);
      } catch (ConflictException e) {
        // XXX we assume name as factor of equality here
        Object existing = null;
        ItemsIterator files = api.getFolderItems(parentId);
        while (files.hasNext()) {
          Object item = files.next();
          if (title.equals("TODO item.getName()")) { // TODO use more complex check if required
            existing = item;
            break;
          }
        }
        if (existing == null) {
          throw e; // we cannot do anything at this level
        } else {
          folder = existing;
        }
      }

      String id = "TODO"; // folder.getId();
      String name = "TODO"; // folder.getName();
      String type = "TODO"; // folder.getType();
      String link = api.getLink(folder);
      String createdBy = "TODO"; // folder.getCreatedBy().getLogin();
      String modifiedBy = "TODO"; // folder.getModifiedBy().getLogin();

      initFolder(folderNode, id, name, type, //
                 link, // link
                 createdBy, // author
                 modifiedBy, // lastUser
                 created,
                 created); // created as modified here
      initCloudItem(folderNode, folder);
      return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateFile(Node fileNode, Calendar modified) throws CloudDriveException, RepositoryException {
      // TODO logic of existing file metadata and parent (location) update.

      Object file = api.updateFile(getParentId(fileNode), getId(fileNode), getTitle(fileNode), modified);
      if (file != null) {
        String id = "TODO"; // file.getId();
        String name = "TODO"; // file.getName();
        String link = api.getLink(file);
        String embedLink = api.getEmbedLink(file);
        String thumbnailLink = link; // TODO need real thumbnail
        String createdBy = "TODO"; // file.getCreatedBy().getLogin();
        Calendar created = Calendar.getInstance(); // api.parseDate(file.getCreatedAt());
        modified = Calendar.getInstance(); // api.parseDate(file.getModifiedAt());
        String modifiedBy = "TODO"; // file.getModifiedBy().getLogin();
        String type = "TODO"; // item.getType();

        initFile(fileNode, id, name, type, link, embedLink, //
                 thumbnailLink, // downloadLink
                 createdBy, // author
                 modifiedBy, // lastUser
                 created,
                 modified);
        initCloudItem(fileNode, file);
      } // else file wasn't changed actually
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateFolder(Node folderNode, Calendar modified) throws CloudDriveException,
                                                                RepositoryException {

      // TODO Update existing folder metadata and parent (location).

      Object folder = api.updateFolder(getParentId(folderNode),
                                       getId(folderNode),
                                       getTitle(folderNode),
                                       modified);
      if (folder != null) {
        String id = "TODO"; // folder.getId();
        String name = "TODO"; // folder.getName();
        String link = api.getLink(folder);
        String type = "TODO"; // folder.getType();
        String createdBy = "TODO"; // folder.getCreatedBy().getLogin();
        Calendar created = Calendar.getInstance(); // api.parseDate(folder.getCreatedAt());
        modified = Calendar.getInstance(); // api.parseDate(folder.getModifiedAt());
        String modifiedBy = "TODO"; // folder.getModifiedBy().getLogin();

        initFolder(folderNode, id, name, type, //
                   link, // link
                   createdBy, // author
                   modifiedBy, // lastUser
                   created,
                   modified);
        initCloudItem(folderNode, folder);
      } // else folder wasn't changed actually
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateFileContent(Node fileNode, Calendar modified, String mimeType, InputStream content) throws CloudDriveException,
                                                                                                         RepositoryException {
      // Update existing file content and its metadata.
      Object file = api.updateFileContent(getParentId(fileNode),
                                          getId(fileNode),
                                          getTitle(fileNode),
                                          modified,
                                          content);
      String id = "TODO"; // file.getId();
      String name = "TODO"; // file.getName();
      String link = api.getLink(file);
      String embedLink = api.getEmbedLink(file);
      String thumbnailLink = link; // TODO need real thumbnail
      String createdBy = "TODO"; // file.getCreatedBy().getLogin();
      Calendar created = Calendar.getInstance(); // api.parseDate(file.getCreatedAt());
      modified = Calendar.getInstance(); // api.parseDate(file.getModifiedAt());
      String modifiedBy = "TODO"; // file.getModifiedBy().getLogin();
      String type = "TODO"; // folder.getType();

      initFile(fileNode, id, name, type, link, embedLink, //
               thumbnailLink, // downloadLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified);
      initCloudItem(fileNode, file);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String copyFile(Node srcFileNode, Node destFileNode) throws CloudDriveException,
                                                               RepositoryException {
      Object file = api.copyFile(getId(srcFileNode), getParentId(destFileNode), getTitle(destFileNode));
      String id = "TODO"; // file.getId();
      String name = "TODO"; // file.getName();
      String link = api.getLink(file);
      String thumbnailLink = link; // TODO need real thumbnail
      String embedLink = api.getEmbedLink(file);
      String createdBy = "TODO"; // file.getCreatedBy().getLogin();
      String modifiedBy = "TODO"; // file.getModifiedBy().getLogin();
      Calendar created = Calendar.getInstance(); // api.parseDate(file.getCreatedAt());
      Calendar modified = Calendar.getInstance(); // api.parseDate(file.getModifiedAt());
      String type = "TODO"; // folder.getType();

      initFile(destFileNode, id, name, type, link, embedLink, //
               thumbnailLink, // thumbnailLink
               createdBy, // author
               modifiedBy, // lastUser
               created,
               modified);
      initCloudItem(destFileNode, file);
      return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String copyFolder(Node srcFolderNode, Node destFolderNode) throws CloudDriveException,
                                                                     RepositoryException {
      Object folder = api.copyFolder(getId(srcFolderNode),
                                     getParentId(destFolderNode),
                                     getTitle(destFolderNode));
      String id = "TODO"; // folder.getId();
      String name = "TODO"; // folder.getName();
      String type = "TODO"; // folder.getType();
      String link = api.getLink(folder);
      String createdBy = "TODO"; // folder.getCreatedBy().getLogin();
      String modifiedBy = "TODO"; // folder.getModifiedBy().getLogin();
      Calendar created = Calendar.getInstance(); // api.parseDate(folder.getCreatedAt());
      Calendar modified = Calendar.getInstance(); // api.parseDate(folder.getModifiedAt());

      initFolder(destFolderNode, id, name, type, //
                 link, // link
                 createdBy, // author
                 modifiedBy, // lastUser
                 created,
                 modified);
      initCloudItem(destFolderNode, folder);
      return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeFile(String id) throws CloudDriveException, RepositoryException {
      api.deleteFile(id);
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
      Object trashed = api.trashFile(id);
      // TODO actual logic to check if file was successfully trashed (or may be not if
      // permissions or anything else prevented that)
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean trashFolder(String id) throws CloudDriveException, RepositoryException {
      Object trashed = api.trashFolder(id);
      // TODO actual logic to check if folder was successfully trashed (or may be not if
      // permissions or anything else prevented that)
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean untrashFile(Node fileNode) throws CloudDriveException, RepositoryException {
      Object untrashed = api.untrashFile(fileAPI.getId(fileNode), fileAPI.getTitle(fileNode));
      // TODO actual logic to check if file was successfully untrashed (or may be not if
      // permissions or anything else prevented that)
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean untrashFolder(Node folderNode) throws CloudDriveException, RepositoryException {
      Object untrashed = api.untrashFolder(fileAPI.getId(folderNode), fileAPI.getTitle(folderNode));
      // TODO actual logic to check if folder was successfully trashed (or may be not if
      // permissions or anything else prevented that)
      return true;
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
    public CloudFile restore(String id, String path) throws NotFoundException,
                                                    CloudDriveException,
                                                    RepositoryException {
      // TODO implement restoration of file by id at its local path (known as part of remove/update)
      throw new SyncNotSupportedException("Restore not supported");
    }
  }

  /**
   * An implementation of {@link SyncCommand} based on an abstract events queue proposed and maintained by the
   * cloud service.
   * 
   */
  protected class EventsSync extends SyncCommand {

    /**
     * Internal API.
     */
    protected final TemplateAPI api;

    /**
     * Events from drive to apply.
     */
    protected EventsIterator    events;

    protected Object            nextEvent;

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
      // TODO implement real sync algorithm based on events from cloud API

      long localChangeId = getChangeId();

      // buffer all items,
      // apply them in proper order (taking in account parent existence),
      // remove already applied (check by event id in history),
      // apply others to local nodes
      // save just applied events as history
      events = api.getEvents(localChangeId);
      iterators.add(events);

      // loop over events and respect this thread interrupted status to cancel the command correctly
      while (events.hasNext() && !Thread.currentThread().isInterrupted()) {
        Object event = events.next();
        Object item = new Object(); // event.getItem();
        String eventType = "TODO"; // event.getEventType();

        String id = "TODO"; // item.getId();
        String name = "TODO"; // item.getName();

        // TODO find parent id
        String parentId = "";

        // TODO find parent node
        Node parent = null;

        if (eventType.equals("ITEM_CREATE")) {
          apply(updateItem(api, item, parent, null));
        } else if (eventType.equals("ITEM_TRASH")) {
          Node node = readNode(parent, name, id);
          // TODO if node can be not found or Null - care about it
          String path = node.getPath();
          node.remove();
          remove(id, path);
        } else if (eventType.equals("ITEM_UNDELETE_VIA_TRASH")) {
          apply(updateItem(api, item, parent, null));
        } else if (eventType.equals("other types...")) {
          // TODO other op logic
        } else {
          LOG.warn("Skipped unexpected change from cloud Event: " + eventType);
        }
      }

      if (!Thread.currentThread().isInterrupted()) {
        if (false) { // TODO if sync via events not possible - then we can run Full Sync
          // EventsSync cannot solve all changes, need run FullSync
          LOG.warn("Not all events applied for cloud sync. Running full sync.");

          // rollback everything from this sync
          rollback(rootNode);

          // we need full sync in this case
          FullSync fullSync = new FullSync();
          fullSync.execLocal();

          changed.clear();
          changed.addAll(fullSync.getFiles());
          removed.clear();
          removed.addAll(fullSync.getRemoved());
        } else {
          // TODO for next syncs needs you may need to save the history
          // consider for saving the history of several hours or even a day

          // update sync position
          setChangeId(events.getChangeId());
        }
      }
    }

    protected Object fetchChilds(String fileId, Node parent) throws CloudDriveException, RepositoryException {
      ItemsIterator items = api.getFolderItems(fileId);
      iterators.add(items);
      while (items.hasNext()) {
        Object item = items.next();
        JCRLocalCloudFile localItem = updateItem(api, item, parent, null);
        if (localItem.isChanged()) {
          apply(localItem);
          if (localItem.isFolder()) {
            // go recursive to the folder
            fetchChilds(localItem.getId(), localItem.getNode());
          }
        }
      }
      return items.parent;
    }

    protected void apply(JCRLocalCloudFile local) {
      if (local.isChanged()) {
        removed.remove(local.getPath());
        changed.add(local);
      }
    }

    protected void remove(String itemId, String itemPath) {
      if (itemPath != null) {
        removed.add(itemPath);
      }
    }
  }

  /**
   * @param user
   * @param driveNode
   * @param sessionProviders
   * @throws CloudDriveException
   * @throws RepositoryException
   */
  protected JCRLocalTemplateDrive(TemplateUser user,
                                  Node driveNode,
                                  SessionProviderService sessionProviders,
                                  NodeFinder finder,
                                  ExtendedMimeTypeResolver mimeTypes) throws CloudDriveException,
      RepositoryException {
    super(user, driveNode, sessionProviders, finder, mimeTypes);
    getUser().api().getToken().addListener(this);
  }

  protected JCRLocalTemplateDrive(API apiBuilder,
                                  TemplateProvider provider,
                                  Node driveNode,
                                  SessionProviderService sessionProviders,
                                  NodeFinder finder,
                                  ExtendedMimeTypeResolver mimeTypes) throws RepositoryException,
      CloudDriveException {
    super(loadUser(apiBuilder, provider, driveNode), driveNode, sessionProviders, finder, mimeTypes);
    getUser().api().getToken().addListener(this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void initDrive(Node driveNode) throws CloudDriveException, RepositoryException {
    super.initDrive(driveNode);

    // TODO other custom things
  }

  /**
   * Load user from the drive Node.
   * 
   * @param apiBuilder {@link API} API builder
   * @param provider {@link TemplateProvider}
   * @param driveNode {@link Node} root of the drive
   * @return {@link TemplateUser}
   * @throws RepositoryException
   * @throws TemplateException
   * @throws CloudDriveException
   */
  protected static TemplateUser loadUser(API apiBuilder, TemplateProvider provider, Node driveNode) throws RepositoryException,
                                                                                                   TemplateException,
                                                                                                   CloudDriveException {
    String username = driveNode.getProperty("ecd:cloudUserName").getString();
    String email = driveNode.getProperty("ecd:userEmail").getString();
    String userId = driveNode.getProperty("ecd:cloudUserId").getString();

    String accessToken = driveNode.getProperty("PROVIDER_ID:oauth2AccessToken").getString();
    String refreshToken;
    try {
      refreshToken = driveNode.getProperty("PROVIDER_ID:oauth2RefreshToken").getString();
    } catch (PathNotFoundException e) {
      refreshToken = null;
    }
    long expirationTime = driveNode.getProperty("PROVIDER_ID:oauth2TokenExpirationTime").getLong();

    TemplateAPI driveAPI = apiBuilder.load(refreshToken, accessToken, expirationTime).build();

    return new TemplateUser(userId, username, email, provider, driveAPI);
  }

  /**
   * {@inheritDoc}
   * 
   * @throws TemplateException
   */
  @Override
  public void onUserTokenRefresh(UserToken token) throws CloudDriveException {
    try {
      jcrListener.disable();
      Node driveNode = rootNode();
      try {
        driveNode.setProperty("PROVIDER_ID:oauth2AccessToken", token.getAccessToken());
        driveNode.setProperty("PROVIDER_ID:oauth2RefreshToken", token.getRefreshToken());
        driveNode.setProperty("PROVIDER_ID:oauth2TokenExpirationTime", token.getExpirationTime());

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
  protected ConnectCommand getConnectCommand() throws DriveRemovedException, RepositoryException {
    return new Connect();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected SyncCommand getSyncCommand() throws DriveRemovedException,
                                        SyncNotSupportedException,
                                        RepositoryException {

    Calendar now = Calendar.getInstance();
    Calendar last = rootNode().getProperty("PROVIDER_ID:changeDate").getDate();

    // XXX we force a full sync (a whole drive traversing) each defined period.
    // We do this for a case when provider will not provide a full history for files connected long time ago
    // and weren't synced day by day (drive was rarely used).
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
      return rootNode().getProperty("PROVIDER_ID:changePosition").getLong();
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
    driveNode.setProperty("PROVIDER_ID:changePosition", id);
    driveNode.setProperty("PROVIDER_ID:changeDate", Calendar.getInstance());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public TemplateUser getUser() {
    return (TemplateUser) user;
  }

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
    getUser().api().updateToken(((TemplateUser) newUser).api().getToken());
  }

  /**
   * Initialize cloud's common specifics of files and folders.
   * 
   * @param localNode {@link Node}
   * @param item {@link Object}
   * @throws RepositoryException
   * @throws TemplateException
   */
  protected void initCloudItem(Node localNode, Object item) throws RepositoryException, TemplateException {
    // TODO init localNode with a data of cloud item

    // Etag and sequence_id used for synchronization
    localNode.setProperty("PROVIDER_ID:etag", "item.getEtag()");
    try {
      String sequenceIdStr = ""; // item.getSequenceId();
      if (sequenceIdStr != null) {
        localNode.setProperty("PROVIDER_ID:sequenceId", Long.parseLong(sequenceIdStr));
      } // else, it's null (root or trash)
    } catch (NumberFormatException e) {
      throw new TemplateException("Error parsing sequence_id of " + localNode.getPath(), e);
    }

    // File/folder size
    // TODO exo's property to show the size: jcr:content's length?
    localNode.setProperty("PROVIDER_ID:size", ""); // item.getSize()

    // properties below not actually used by the Cloud Drive,
    // they are just for information available to PLF user
    localNode.setProperty("PROVIDER_ID:ownedBy", ""); // item.getOwnedBy().getLogin()
    localNode.setProperty("PROVIDER_ID:description", ""); // item.getDescription()
  }

  /**
   * Update or create a local node of Cloud File. If the node is <code>null</code> then it will be open on the
   * given parent and created if not already exists.
   * 
   * @param api {@link TemplateAPI}
   * @param item {@link Object}
   * @param parent {@link Node}
   * @param node {@link Node}, can be <code>null</code>
   * @return {@link JCRLocalCloudFile}
   * @throws RepositoryException for storage errors
   * @throws CloudDriveException for drive or format errors
   */
  protected JCRLocalCloudFile updateItem(TemplateAPI api, Object item, Node parent, Node node) throws RepositoryException,
                                                                                              CloudDriveException {
    // TODO fill with actual logic of item update

    String id = ""; // item.getId();
    String name = ""; // item.getName();
    boolean isFolder = false; // item instanceof APIFolder;
    String type = ""; // isFolder ? item.getType() : findMimetype(name);
    // TODO type mode not required if provider's preview/edit will be used (embedded in eXo)
    String typeMode = mimeTypes.getMimeTypeMode(type, name);
    String itemHash = ""; // item.getEtag()

    // read/create local node if not given
    if (node == null) {
      if (isFolder) {
        node = openFolder(id, name, parent);
      } else {
        node = openFile(id, name, parent);
      }
    }

    boolean changed = node.isNew() || !node.getProperty("PROVIDER_ID:etag").getString().equals(itemHash);

    Calendar created = Calendar.getInstance(); // api.parseDate(item.getCreatedAt());
    Calendar modified = Calendar.getInstance(); // api.parseDate(item.getModifiedAt());
    String createdBy = ""; // item.getCreatedBy().getLogin();
    String modifiedBy = ""; // item.getModifiedBy().getLogin();

    String link, embedLink, thumbnailLink;
    if (isFolder) {
      link = embedLink = api.getLink(item);
      thumbnailLink = null;
      if (changed) {
        initFolder(node, id, name, type, // type=folder
                   link, // gf.getAlternateLink(),
                   createdBy, // gf.getOwnerNames().get(0),
                   modifiedBy, // gf.getLastModifyingUserName(),
                   created,
                   modified);
        initCloudItem(node, item);
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
        initCloudItem(node, item);
      }
    }

    return new JCRLocalCloudFile(node.getPath(),
                                 id,
                                 name,
                                 link,
                                 editLink(node),
                                 embedLink,
                                 thumbnailLink,
                                 type,
                                 typeMode,
                                 createdBy,
                                 modifiedBy,
                                 created,
                                 modified,
                                 isFolder,
                                 false,
                                 node,
                                 changed);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String previewLink(Node fileNode) throws RepositoryException {
    // TODO return specially formatted preview link or using a special URL if that required by the cloud API
    return super.previewLink(fileNode);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String editLink(Node fileNode) {
    // TODO Return actual link for embedded editing (in iframe) or null if edit not supported
    return null;
  }

  protected boolean notInRange(String path, Collection<String> range) {
    for (String p : range) {
      if (path.startsWith(p)) {
        return false;
      }
    }
    return true;
  }

}
