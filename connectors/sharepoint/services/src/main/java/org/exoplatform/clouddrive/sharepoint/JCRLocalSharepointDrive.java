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
package org.exoplatform.clouddrive.sharepoint;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.cmis.CMISException;
import org.exoplatform.clouddrive.cmis.CMISUser;
import org.exoplatform.clouddrive.cmis.JCRLocalCMISDrive;
import org.exoplatform.clouddrive.jcr.NodeFinder;
import org.exoplatform.clouddrive.sharepoint.SharepointConnector.API;
import org.exoplatform.clouddrive.utils.ExtendedMimeTypeResolver;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

/**
 * Local drive for CMIS provider.<br>
 * 
 */
public class JCRLocalSharepointDrive extends JCRLocalCMISDrive {

  /**
   * @param user
   * @param driveNode
   * @param sessionProviders
   * @throws CloudDriveException
   * @throws RepositoryException
   */
  protected JCRLocalSharepointDrive(SharepointUser user,
                                    Node driveNode,
                                    SessionProviderService sessionProviders,
                                    NodeFinder finder,
                                    ExtendedMimeTypeResolver mimeTypes,
                                    String exoURL) throws CloudDriveException, RepositoryException {
    super(user, driveNode, sessionProviders, finder, mimeTypes, exoURL);
    SharepointAPI api = user.api();
    saveAccess(driveNode, api.getPassword(), api.getServiceURL(), api.getRepositoryId());
  }

  protected JCRLocalSharepointDrive(API apiBuilder,
                                    Node driveNode,
                                    SessionProviderService sessionProviders,
                                    NodeFinder finder,
                                    ExtendedMimeTypeResolver mimeTypes,
                                    String exoURL) throws RepositoryException, CloudDriveException {
    super(loadUser(apiBuilder, driveNode), driveNode, sessionProviders, finder, mimeTypes, exoURL);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void initDrive(Node driveNode) throws CloudDriveException, RepositoryException {
    super.initDrive(driveNode);

    // SharePoint specific info
    driveNode.setProperty("sharepoint:siteURL", getUser().getSiteURL());
    driveNode.setProperty("sharepoint:siteTitle", getUser().getSiteTitle());
    driveNode.setProperty("sharepoint:userLogin", getUser().getSiteUser().getLoginName());
    driveNode.setProperty("sharepoint:userTitle", getUser().getSiteUser().getTitle());
  }

  /**
   * Load user from the drive Node.
   * 
   * @param apiBuilder {@link API} API builder
   * @param provider {@link SharepointProvider}
   * @param driveNode {@link Node} root of the drive
   * @return {@link CMISUser}
   * @throws RepositoryException
   * @throws SharepointException
   * @throws CloudDriveException
   */
  protected static SharepointUser loadUser(API apiBuilder, Node driveNode) throws RepositoryException,
                                                                          SharepointException,
                                                                          CloudDriveException {
    SharepointUser user = (SharepointUser) JCRLocalCMISDrive.loadUser(apiBuilder, driveNode);
    return user;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SharepointUser getUser() {
    return (SharepointUser) user;
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
    super.initCMISItem(localNode, item);

    // TODO add specific props
    // localNode.setProperty("cmiscd:refreshTimestamp", item.getRefreshTimestamp());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void readNodes(Node parent, Map<String, List<Node>> nodes, boolean deep) throws RepositoryException {
    // gather original mappings
    super.readNodes(parent, nodes, deep);

    final SharepointAPI api = getUser().api();

    // reconstruct the map in its order but with double mappings for files: by simple ID and by SP document ID
    // simple ID actual for sync based on Changes Log where SP doesn't show suffixed IDs of documents
    Map<String, List<Node>> newNodes = new LinkedHashMap<String, List<Node>>();
    for (Map.Entry<String, List<Node>> me : nodes.entrySet()) {
      if (me.getValue().size() > 0) {
        if (fileAPI.isFile(me.getValue().get(0))) {
          newNodes.put(api.simpleId(me.getKey()), me.getValue());
          newNodes.put(api.documentId(me.getKey()), me.getValue());
        } else {
          newNodes.put(me.getKey(), me.getValue());
        }
      }
    }

    // replace the map content
    nodes.clear();
    nodes.putAll(newNodes);
  }

}
