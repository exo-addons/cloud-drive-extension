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

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.clouddrive.dropbox.DropboxConnector.API;
import org.exoplatform.services.jcr.RepositoryService;

import javax.jcr.RepositoryException;

/**
 * Dropbox provider.
 * 
 */
public class DropboxProvider extends CloudProvider {

  protected final API            authBilder;

  protected final String            redirectURL;

  protected final RepositoryService jcrService;

  /**
   * @param id
   * @param name
   * @param authURL
   * @param redirectURL
   * @param jcrService
   */
  public DropboxProvider(String id, String name, API authBilder, String redirectURL, RepositoryService jcrService) {
    super(id, name);
    this.authBilder = authBilder;
    this.redirectURL = redirectURL;
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
    String authURL = authBilder.authLink(authState);
    return authURL;
  }

  /**
   * @return the redirectURL
   */
  public String getRedirectURL() {
    return redirectURL;
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
