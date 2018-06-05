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
package org.exoplatform.clouddrive.PROVIDER_ID;

import javax.jcr.RepositoryException;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.services.jcr.RepositoryService;

/**
 * Template provider. TODO it actually copies GoogleDrive provider by the code
 * because of OAuth2.
 */
public class TemplateProvider extends CloudProvider {

  /** The auth URL. */
  protected final String            authURL;

  /** The redirect URL. */
  protected final String            redirectURL;

  /** The jcr service. */
  protected final RepositoryService jcrService;

  /**
   * Instantiates a new template provider.
   *
   * @param id the id
   * @param name the name
   * @param authURL the auth URL
   * @param redirectURL the redirect URL
   * @param jcrService the jcr service
   */
  public TemplateProvider(String id, String name, String authURL, String redirectURL, RepositoryService jcrService) {
    super(id, name);
    this.authURL = authURL;
    this.redirectURL = redirectURL;
    this.jcrService = jcrService;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getAuthURL() throws CloudDriveException {
    if (jcrService != null) {
      try {
        String currentRepo = jcrService.getCurrentRepository().getConfiguration().getName();
        return authURL.replace(CloudProvider.AUTH_NOSTATE, currentRepo);
      } catch (RepositoryException e) {
        throw new CloudDriveException(e);
      }
    } else {
      return authURL;
    }
  }

  /**
   * Gets the redirect URL.
   *
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

}
