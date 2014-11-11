/*
 * Copyright (C) 2014 eXo Platform SAS.
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

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.clouddrive.cmis.CMISAPI;
import org.exoplatform.clouddrive.cmis.CMISUser;
import org.exoplatform.clouddrive.sharepoint.SharepointAPI.User;

import javax.jcr.RepositoryException;

/**
 * Sharepoint user.
 * 
 */
public class SharepointUser extends CMISUser {

  /**
   * An user in-memory POJO.
   * 
   * @param id {@link String}
   * @param username {@link String}
   * @param email {@link String}
   * @param provider {@link CloudProvider}
   * @param api {@link SharepointAPI}
   */
  public SharepointUser(String id, String username, String email, CloudProvider provider, SharepointAPI api) {
    super(id, username, email, provider, api);
  }

  /**
   * Internal API.
   * 
   * @return {@link CMISAPI} instance authenticated for this user.
   */
  protected SharepointAPI api() {
    return (SharepointAPI) api;
  }

  /**
   * {@inheritDoc}
   * 
   * @throws CloudDriveException
   */
  @Override
  public String createDriveTitle() throws RepositoryException, CloudDriveException {
    StringBuilder title = new StringBuilder();

    String siteTitle = getSiteTitle();
    if (siteTitle != null && siteTitle.length() > 0) {
      // use SP site name as node name prefix
      title.append(siteTitle);
      title.append(" - ");
    } else {
      String predefinedName = getPredefinedRepositoryName();
      if (predefinedName != null) {
        // use predefined name of the CMIS repo as a node name prefix
        title.append(predefinedName);
      } else {
        // use super's name
        return super.createDriveTitle();
      }
    }
    title.append(getRepositoryName());
    title.append(" - ");
    title.append(getUserTitle());
    return title.toString();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getEmail() {
    return getSiteUser().getEmail();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getServiceName() {
    return getProvider().getName();
  }

  // ***** specifics ******

  /**
   * Currently connected SharePoint site name. Its name may be the same as {@link #getRepositoryName()} but
   * this name retrieved via native API.
   * 
   * @return
   */
  public String getSiteTitle() {
    return api().getSiteTitle();
  }

  /**
   * An URl of currently connected SharePoint site. Retrieved via native API.
   * 
   * @return
   */
  public String getSiteURL() {
    return api().getSiteURL();
  }

  /**
   * Current user in SharePoint CMIS repository. Retrieved via native API.
   * 
   * @return
   */
  public User getSiteUser() {
    return api().getSiteUser();
  }

}
