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
package org.exoplatform.clouddrive.dropbox;

import javax.jcr.RepositoryException;

import org.exoplatform.services.cms.clouddrives.CloudDriveException;
import org.exoplatform.services.cms.clouddrives.CloudProvider;
import org.exoplatform.services.cms.clouddrives.CloudUser;
import org.exoplatform.services.cms.clouddrives.DriveRemovedException;

/**
 * Template cloud user.
 */
public class DropboxUser extends CloudUser {

  /** The api. */
  protected final DropboxAPI api;

  /**
   * An user in-memory POJO.
   * 
   * @param id {@link String}
   * @param username {@link String}
   * @param email {@link String}
   * @param provider {@link CloudProvider}
   * @param api {@link DropboxAPI}
   */
  public DropboxUser(String id, String username, String email, CloudProvider provider, DropboxAPI api) {
    super(id, username, email, provider);
    this.api = api;
  }

  /**
   * Dropbox API.
   * 
   * @return {@link DropboxAPI} instance authenticated for this user.
   */
  DropboxAPI api() {
    return api;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DropboxProvider getProvider() {
    return (DropboxProvider) super.getProvider();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String createDriveTitle() throws RepositoryException, DriveRemovedException, CloudDriveException {
    StringBuilder title = new StringBuilder();
    title.append(getServiceName());
    title.append(" - ");
    title.append(getUsername());
    return title.toString();
  }

}
