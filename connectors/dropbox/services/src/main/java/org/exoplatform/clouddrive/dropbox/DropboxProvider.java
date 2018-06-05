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

import javax.jcr.RepositoryException;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.clouddrive.dropbox.DropboxConnector.API;
import org.exoplatform.services.jcr.RepositoryService;

/**
 * Dropbox provider.
 */
public class DropboxProvider extends CloudProvider {

  /** The auth bilder. */
  protected final API               authFactory;

  /** The jcr service. */
  protected final RepositoryService jcrService;

  /**
   * Instantiates a new dropbox provider.
   *
   * @param id the id
   * @param name the name
   * @param authFactory the API factory
   * @param jcrService the jcr service
   */
  public DropboxProvider(String id, String name, API authFactory, RepositoryService jcrService) {
    super(id, name);
    this.authFactory = authFactory;
    this.jcrService = jcrService;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getAuthURL() throws CloudDriveException {
    String authState;
    if (jcrService != null) {
      try {
        authState = jcrService.getCurrentRepository().getConfiguration().getName();
      } catch (RepositoryException e) {
        throw new CloudDriveException(e);
      }
    } else {
      authState = AUTH_NOSTATE;
    }
    String authURL = authFactory.authLink(authState);
    return authURL;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean retryOnProviderError() {
    // repeat on error
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getErrorMessage(String error, String errorDescription) {
    return error; // omit description for users
  }
}
