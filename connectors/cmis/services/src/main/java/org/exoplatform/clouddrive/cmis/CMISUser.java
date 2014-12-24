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

import org.apache.chemistry.opencmis.client.api.Repository;
import org.exoplatform.clouddrive.CloudDriveAccessException;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.cmis.CMISProvider.AtomPub;

import java.util.List;

import javax.jcr.RepositoryException;

/**
 * CMIS user.
 * 
 */
public class CMISUser extends CloudUser {

  protected final CMISAPI api;

  /**
   * An user in-memory POJO.
   * 
   * @param id {@link String}
   * @param username {@link String}
   * @param email {@link String}
   * @param provider {@link CloudProvider}
   * @param api {@link CMISAPI}
   */
  public CMISUser(String id, String username, String email, CloudProvider provider, CMISAPI api) {
    super(id, username, email, provider);
    this.api = api;
  }

  /**
   * Internal API.
   * 
   * @return {@link CMISAPI} instance authenticated for this user.
   */
  protected CMISAPI api() {
    return api;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String createDriveTitle() throws RepositoryException, DriveRemovedException, CloudDriveException {
    StringBuilder title = new StringBuilder();

    String predefinedName = getPredefinedRepositoryName();
    if (predefinedName != null) {
      // use predefined name of the CMIS repo as a node name prefix
      title.append(predefinedName);
    } else {
      // if not predefined then use product name/version as node name prefix
      title.append(api().getVendorName());
      title.append(' ');
      title.append("CMIS"); // api().getProductVersion()
    }

    title.append(" - ");
    title.append(getRepositoryName());
    title.append(" - ");
    title.append(getUserTitle());
    return title.toString();
  }

  /**
   * Current user repository ID.
   * 
   * @return {@link String} user's repository ID
   */
  public String getRepositoryId() {
    return api().getRepositoryId();
  }

  /**
   * Current user repository name.
   * 
   * @return {@link String} user's repository name
   */
  public String getRepositoryName() {
    return api().getRepositoryName();
  }

  /**
   * Current user title.
   * 
   * @return {@link String} user name
   */
  public String getUserTitle() {
    return api().getUserTitle();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CMISProvider getProvider() {
    return (CMISProvider) super.getProvider();
  }

  /**
   * Available CMIS repositories for this user.
   * 
   * @return List of repositories.
   * @throws CloudDriveAccessException
   * @throws CMISException
   */
  public List<Repository> getRepositories() throws CloudDriveAccessException, CMISException {
    return api().getRepositories();
  }

  /**
   * Set current CMIS repository ID for operations of this user.
   * 
   * @param repositoryId {@link String}
   * @throws RefreshAccessException
   */
  public void setRepositoryId(String repositoryId) throws CMISException, RefreshAccessException {
    api().initRepository(repositoryId);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getServiceName() {
    return api().getVendorName() + " " + getProvider().getName();
  }

  // **** internals *****

  /**
   * Predefined name for this user CMIS repository or <code>null</code> if repository wasn't predefined in
   * configuration or by administrator.
   * 
   * @return {@link String}
   */
  protected String getPredefinedRepositoryName() {
    for (AtomPub predefined : getProvider().getPredefinedAtompubServices()) {
      if (predefined.getUrl().equals(api.getServiceURL())) {
        return predefined.getName();
      }
    }
    return null;
  }
}
