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

import javax.jcr.RepositoryException;

import org.exoplatform.services.cms.clouddrives.CloudDriveException;
import org.exoplatform.services.cms.clouddrives.CloudProvider;
import org.exoplatform.services.jcr.RepositoryService;

/**
 * Box provider copies GoogleDrive's provider by the code because of OAuth2.
 * Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: BoxProvider.java 00000 Aug 30, 2013 pnedonosko $
 */
public class BoxProvider extends CloudProvider {

  /** The auth URL. */
  protected final String            authURL;

  /** The redirect URL. */
  protected final String            redirectURL;

  /** The login SSO. */
  protected final boolean           loginSSO;

  /** The jcr service. */
  protected final RepositoryService jcrService;

  /**
   * Instantiates a new box provider.
   *
   * @param id the id
   * @param name the name
   * @param authURL the auth URL
   * @param redirectURL the redirect URL
   * @param loginSSO the login SSO
   * @param jcrService the jcr service
   */
  public BoxProvider(String id, String name, String authURL, String redirectURL, boolean loginSSO, RepositoryService jcrService) {
    super(id, name);
    this.authURL = authURL;
    this.redirectURL = redirectURL;
    this.loginSSO = loginSSO;
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
   * Checks if is login SSO.
   *
   * @return the loginSSO
   */
  public boolean isLoginSSO() {
    return loginSSO;
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
