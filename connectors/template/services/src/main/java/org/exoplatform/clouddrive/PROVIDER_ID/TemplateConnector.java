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

import org.exoplatform.clouddrive.CloudDrive;
import org.exoplatform.clouddrive.CloudDriveConnector;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.ConfigurationException;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudDrive;
import org.exoplatform.clouddrive.jcr.NodeFinder;
import org.exoplatform.clouddrive.utils.ExtendedMimeTypeResolver;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

/**
 * Template of Cloud Drive Connector. Fill the class with actual implementation.<br>
 * 
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: TemplateConnector.java 00000 Aug 30, 2013 pnedonosko $
 * 
 */
public class TemplateConnector extends CloudDriveConnector {

  /**
   * Internal API builder (logic based on OAuth2 flow used in Google Drive and Box connectors).
   */
  class API {
    
    /** The access token. */
    String code, refreshToken, accessToken;

    /** The expiration time. */
    long   expirationTime;

    /**
     * Authenticate to the API with OAuth2 code returned on callback url.
     * 
     * @param code String
     * @return this API
     */
    API auth(String code) {
      this.code = code;
      return this;
    }

    /**
     * Authenticate to the API with locally stored tokens.
     *
     * @param refreshToken the refresh token
     * @param accessToken the access token
     * @param expirationTime the expiration time
     * @return this API
     */
    API load(String refreshToken, String accessToken, long expirationTime) {
      this.refreshToken = refreshToken;
      this.accessToken = accessToken;
      this.expirationTime = expirationTime;
      return this;
    }

    /**
     * Build API.
     * 
     * @return {@link TemplateAPI}
     * @throws TemplateException if error happen during communication with Google Drive services
     * @throws CloudDriveException if cannot load local tokens
     */
    TemplateAPI build() throws TemplateException, CloudDriveException {
      if (code != null && code.length() > 0) {
        // build API based on OAuth2 code
        return new TemplateAPI(getClientId(), getClientSecret(), code, getProvider().getRedirectURL());
      } else {
        // build API based on locally stored tokens
        return new TemplateAPI(getClientId(), getClientSecret(), accessToken, refreshToken, expirationTime);
      }
    }
  }

  /**
   * Instantiates a new template connector.
   *
   * @param jcrService the jcr service
   * @param sessionProviders the session providers
   * @param finder the finder
   * @param mimeTypes the mime types
   * @param params the params
   * @throws ConfigurationException the configuration exception
   */
  public TemplateConnector(RepositoryService jcrService,
                           SessionProviderService sessionProviders,
                           NodeFinder finder,
                           ExtendedMimeTypeResolver mimeTypes,
                           InitParams params) throws ConfigurationException {
    super(jcrService, sessionProviders, finder, mimeTypes, params);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected TemplateProvider getProvider() {
    // we cast to get an access to methods of the implementation
    return (TemplateProvider) super.getProvider();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudProvider createProvider() {
    String redirectURL = redirectLink();

    StringBuilder oauthURL = new StringBuilder();
    oauthURL.append("https://");
    oauthURL.append("www.YOURCLOUDHOST.com/api/oauth2/authorize?");
    oauthURL.append("response_type=code&client_id=");
    String clientId = getClientId();
    try {
      oauthURL.append(URLEncoder.encode(clientId, "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      LOG.warn("Cannot encode client id " + clientId + ":" + e);
      oauthURL.append(clientId);
    }
    oauthURL.append("&state=");
    try {
      oauthURL.append(URLEncoder.encode("_no_state", "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      LOG.warn("Cannot encode _no_state:" + e);
      oauthURL.append("_no_state");
    }
    oauthURL.append("&redirect_uri=");
    // actual uri will be appended below to avoid double encoding in case of SSO

    StringBuilder authURL = new StringBuilder();
    try {
      oauthURL.append(URLEncoder.encode(redirectURL.toString(), "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      LOG.warn("Cannot encode redirect URL " + redirectURL.toString() + ":" + e);
      oauthURL.append(redirectURL);
    }
    authURL.append(oauthURL);

    return new TemplateProvider(getProviderId(), getProviderName(), authURL.toString(), redirectURL.toString(), jcrService);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudUser authenticate(Map<String, String> params) throws CloudDriveException {
    String code = params.get(OAUTH2_CODE);
    String state = params.get(OAUTH2_STATE);
    String error = params.get(OAUTH2_ERROR);
    String errorDescription = params.get(OAUTH2_ERROR_DESCRIPTION);
    if (code != null && code.length() > 0) {
      TemplateAPI driveAPI = new API().auth(code).build();
      Object apiUser = driveAPI.getCurrentUser();
      TemplateUser user = new TemplateUser("apiUser.getId()", "apiUser.getName()", "apiUser.getLogin()", provider, driveAPI);
      return user;
    } else {
      throw new CloudDriveException("Access code should not be null or empty");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudDrive createDrive(CloudUser user, Node driveNode) throws CloudDriveException, RepositoryException {
    if (user instanceof TemplateUser) {
      TemplateUser apiUser = (TemplateUser) user;
      JCRLocalTemplateDrive drive = new JCRLocalTemplateDrive(apiUser, driveNode, sessionProviders, jcrFinder, mimeTypes);
      return drive;
    } else {
      throw new CloudDriveException("Not cloud user: " + user);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudDrive loadDrive(Node driveNode) throws DriveRemovedException, CloudDriveException, RepositoryException {
    JCRLocalCloudDrive.checkNotTrashed(driveNode);
    JCRLocalCloudDrive.migrateName(driveNode);
    JCRLocalTemplateDrive drive = new JCRLocalTemplateDrive(new API(),
                                                            getProvider(),
                                                            driveNode,
                                                            sessionProviders,
                                                            jcrFinder,
                                                            mimeTypes);
    return drive;
  }

}
