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
import org.exoplatform.clouddrive.cmis.CMISAPI;
import org.exoplatform.clouddrive.cmis.CMISException;
import org.exoplatform.clouddrive.cmis.CMISProvider;
import org.exoplatform.clouddrive.cmis.CMISUser;
import org.exoplatform.clouddrive.cmis.JCRLocalCMISDrive;
import org.exoplatform.clouddrive.jcr.NodeFinder;
import org.exoplatform.clouddrive.sharepoint.SharepointConnector.API;
import org.exoplatform.commons.utils.MimeTypeResolver;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.gatein.common.util.Base64;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.atomic.AtomicLong;

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
                                    NodeFinder finder) throws CloudDriveException, RepositoryException {
    super(user, driveNode, sessionProviders, finder);
    SharepointAPI api = user.api();
    saveAccess(driveNode, api.getPassword(), api.getServiceURL(), api.getRepositoryId());
  }

  protected JCRLocalSharepointDrive(API apiBuilder,
                                    CMISProvider provider,
                                    Node driveNode,
                                    SessionProviderService sessionProviders,
                                    NodeFinder finder) throws RepositoryException, CloudDriveException {
    super(loadUser(apiBuilder, provider, driveNode), driveNode, sessionProviders, finder);
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
  protected static SharepointUser loadUser(API apiBuilder, CMISProvider provider, Node driveNode) throws RepositoryException,
                                                                                                 SharepointException,
                                                                                                 CloudDriveException {
    SharepointUser user = (SharepointUser) JCRLocalCMISDrive.loadUser(apiBuilder, provider, driveNode);
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
