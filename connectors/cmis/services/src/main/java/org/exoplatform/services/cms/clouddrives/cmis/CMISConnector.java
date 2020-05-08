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
package org.exoplatform.services.cms.clouddrives.cmis;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.exoplatform.services.cms.clouddrives.cmis.login.AuthenticationException;
import org.exoplatform.services.cms.clouddrives.cmis.login.CodeAuthentication;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.cms.clouddrives.CloudDrive;
import org.exoplatform.services.cms.clouddrives.CloudDriveConnector;
import org.exoplatform.services.cms.clouddrives.CloudDriveException;
import org.exoplatform.services.cms.clouddrives.CloudDriveServiceImpl;
import org.exoplatform.services.cms.clouddrives.CloudProvider;
import org.exoplatform.services.cms.clouddrives.CloudUser;
import org.exoplatform.services.cms.clouddrives.ConfigurationException;
import org.exoplatform.services.cms.clouddrives.DriveRemovedException;
import org.exoplatform.services.cms.clouddrives.jcr.JCRLocalCloudDrive;
import org.exoplatform.services.cms.clouddrives.jcr.NodeFinder;
import org.exoplatform.services.cms.clouddrives.utils.ExtendedMimeTypeResolver;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

/**
 * CMIS Connector.<br>
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: CMISConnector.java 00000 Aug 30, 2013 pnedonosko $
 */
public class CMISConnector extends CloudDriveConnector {

  /** The Constant CONFIG_PREDEFINED. */
  protected static final String CONFIG_PREDEFINED = "";

  /**
   * Internal API builder (logic based on OAuth2 flow used in Google Drive and
   * Box connectors).
   */
  protected class API {

    /** The password. */
    protected String serviceUrl, user, password;

    /**
     * Authenticate to the API with user and password.
     * 
     * @param user {@link String}
     * @param password {@link String}
     * @return {@link API}
     */
    protected API auth(String user, String password) {
      this.user = user;
      this.password = password;
      return this;
    }

    /**
     * Set CMIS service URL.
     * 
     * @param serviceUrl {@link String}
     * @return {@link API}
     */
    protected API serviceUrl(String serviceUrl) {
      this.serviceUrl = serviceUrl;
      return this;
    }

    /**
     * Build API.
     * 
     * @return {@link CMISAPI}
     * @throws CMISException if error happen during communication with CMIS
     *           services
     * @throws CloudDriveException if cannot load local tokens
     */
    protected CMISAPI build() throws CMISException, CloudDriveException {
      if (user == null || password == null) {
        throw new CloudDriveException("Cannot create API: user required");
      }
      if (serviceUrl == null) {
        throw new CloudDriveException("Cannot create API: service URL required");
      }
      return new CMISAPI(serviceUrl, user, password);
    }

    /**
     * Create {@link CMISUser} instance.
     * 
     * @param userId {@link String}
     * @param userName {@link String}
     * @param email {@link String}
     * @param api {@link CMISAPI}
     * @return {@link CMISUser} instance
     */
    protected CMISUser createUser(String userId, String userName, String email, CMISAPI api) {
      return new CMISUser(userId, userName, email, getProvider(), api);
    }
  }

  /**
   * The Class AuthFlow.
   */
  class AuthFlow {

    /** The user. */
    final CMISUser user;

    /** The identity. */
    final CodeAuthentication.Identity identity;

    /**
     * Instantiates a new auth flow.
     *
     * @param user the user
     * @param identity the identity
     */
    AuthFlow(CMISUser user, CodeAuthentication.Identity identity) {
      this.user = user;
      this.identity = identity;
    }
  }

  /** The code auth. */
  private final CodeAuthentication                  codeAuth;

  /** The users. */
  private final ConcurrentHashMap<String, AuthFlow> users = new ConcurrentHashMap<String, AuthFlow>();

  /**
   * Instantiates a new CMIS connector.
   *
   * @param jcrService the jcr service
   * @param sessionProviders the session providers
   * @param finder the finder
   * @param mimeTypes the mime types
   * @param params the params
   * @param codeAuth the code auth
   * @throws ConfigurationException the configuration exception
   */
  public CMISConnector(RepositoryService jcrService,
                       SessionProviderService sessionProviders,
                       NodeFinder finder,
                       ExtendedMimeTypeResolver mimeTypes,
                       InitParams params,
                       CodeAuthentication codeAuth)
      throws ConfigurationException {
    super(jcrService, sessionProviders, finder, mimeTypes, params);
    this.codeAuth = codeAuth;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CMISProvider getProvider() {
    // we cast to get an access to methods of the implementation
    return (CMISProvider) super.getProvider();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudProvider createProvider() throws ConfigurationException {
    String redirectURL = redirectLink();

    // Auth URL lead to a webpage on eXo side, it should ask for
    // username/password and store somehow on the
    // serverside for late use by the connect flow
    StringBuilder authURL = new StringBuilder();
    authURL.append(getConnectorSchema());
    authURL.append("://");
    authURL.append(getConnectorHost());
    authURL.append('/');
    authURL.append(PortalContainer.getCurrentPortalContainerName());
    authURL.append("/clouddrive/");
    // by this auth provider id, dedicated CMIS connectors can use CMIS
    // connector's login form
    authURL.append(getAuthProviderId());
    authURL.append("/login?state=");
    try {
      authURL.append(URLEncoder.encode(CloudProvider.AUTH_NOSTATE, "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      LOG.warn("Cannot encode state " + CloudProvider.AUTH_NOSTATE + ":" + e);
      authURL.append(CloudProvider.AUTH_NOSTATE);
    }
    authURL.append("&providerId=");
    authURL.append(getProviderId());
    authURL.append("&redirect_uri=");
    try {
      authURL.append(URLEncoder.encode(redirectURL.toString(), "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      LOG.warn("Cannot encode redirect URL " + redirectURL.toString() + ":" + e);
      authURL.append(redirectURL);
    }

    CMISProvider provider = new CMISProvider(getProviderId(), getProviderName(), authURL.toString(), jcrService);

    provider.initPredefined(predefinedServices);
    return provider;
  }

  /**
   * Authenticate an user by OAuth2 code (from params) and return
   * {@link CMISUser} instance. CMIS connector requires custom two step
   * authentication instead of OAuth2 flow. This two-step flow is similar to
   * OAuth2 where user does authentication to the service and then authorizes
   * via OAuth2 protocol. This is done to support other parts of Cloud Drive
   * add-on.<br>
   * On first call of this method (first step), given code will be exchanged on
   * user identity and {@link CMISUser} instance will be created. This user
   * instance will be stored in the connector mapped by the code. At the same
   * time the user identity should be late initialized with a context (CMIS
   * repository to connect by the user). If the identity will not be initialized
   * before a second call (second step), this method will fail with
   * {@link CloudDriveException}.<br>
   * CMIS connector doesn't manage the user identity initialization or any other
   * extra steps. This should be done outside this connector (e.g. via dedicated
   * authenticator).<br>
   * CMIS connector will detect if dedicated connector available for given
   * service and then will return user instance initialized by that connector.
   *
   * @param params the params
   * @return {@link CMISUser}
   * @throws CloudDriveException the cloud drive exception
   */
  @Override
  protected CMISUser authenticate(Map<String, String> params) throws CloudDriveException {
    String code = params.get(OAUTH2_CODE);
    if (code != null && code.length() > 0) {
      AuthFlow userFlow = users.remove(code);
      if (userFlow == null) {
        // exchange the code on identity and create an user
        try {
          CodeAuthentication.Identity userId = codeAuth.exchangeCode(code);

          // use default implementation
          CMISUser user = createUser(userId);

          // we ignore something mapped previously as it is almost not possible
          // due to uniqueness of the code
          users.put(code, new AuthFlow(user, userId));
          return user;
        } catch (AuthenticationException e) {
          throw new CloudDriveException("Authentication failed: " + e.getMessage(), e);
        }
      } else {
        // complete the user by setting the code context (CMIS repository here)
        CMISUser user = userFlow.user;
        CodeAuthentication.Identity userId = userFlow.identity;
        String context = userId.getServiceContext();
        if (context != null) {
          // set current repo first (before getting repo info)!
          user.setRepositoryId(context);

          RepositoryInfo repo = user.api().getRepositoryInfo();
          // XXX a bit nasty way to get things
          CloudDriveServiceImpl cdService =
                                          (CloudDriveServiceImpl) ExoContainerContext.getCurrentContainer()
                                                                                     .getComponentInstanceOfType(CloudDriveServiceImpl.class);
          for (CloudDriveConnector cdc : cdService.getConnectors()) {
            if (cdc instanceof CMISConnectorImpl) {
              CMISConnectorImpl cimpl = (CMISConnectorImpl) cdc;
              if (cimpl.hasSupport(repo)) {
                CMISConnector c = cimpl.getConnector();
                if (c == this) {
                  // it is already a dedicated connector and user
                  break;
                }
                // create user instance dedicated to the CMIS connector
                // implementation
                user = c.createUser(userId);
                user.setRepositoryId(context); // set context for the new user!
                break;
              }
            }
          }

          return user;
        } else {
          throw new CloudDriveException("CMIS repository not defined");
        }
      }
    } else {
      throw new CloudDriveException("Access code should not be null or empty");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudDrive createDrive(CloudUser user, Node driveNode) throws CloudDriveException, RepositoryException {
    if (user instanceof CMISUser) {
      CMISUser apiUser = (CMISUser) user;
      JCRLocalCMISDrive drive = new JCRLocalCMISDrive(apiUser, driveNode, sessionProviders, jcrFinder, mimeTypes, exoURL());
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
    JCRLocalCMISDrive drive = new JCRLocalCMISDrive(new API(), driveNode, sessionProviders, jcrFinder, mimeTypes, exoURL());
    return drive;
  }

  // ***** specifics ******

  /**
   * Exo URL.
   *
   * @return the string
   */
  protected String exoURL() {
    StringBuilder exoURL = new StringBuilder();
    exoURL.append(getConnectorSchema());
    exoURL.append("://");
    exoURL.append(getConnectorHost());
    return exoURL.toString();
  }

  /**
   * Create {@link CMISAPI} instance.<br>
   *
   * @param userId {@link CodeAuthentication.Identity}
   * @return {@link CMISAPI} instance
   * @throws CMISException the CMIS exception
   * @throws CloudDriveException the cloud drive exception
   */
  protected CMISAPI createAPI(CodeAuthentication.Identity userId) throws CMISException, CloudDriveException {
    return new API().auth(userId.getUser(), userId.getPassword()).serviceUrl(userId.getServiceURL()).build();
  }

  /**
   * Create an instance of {@link CMISUser} using data from given
   * {@link CodeAuthentication.Identity} or/and {@link CMISAPI}.
   *
   * @param userId {@link CodeAuthentication.Identity}
   * @return {@link CMISUser}
   * @throws CMISException the CMIS exception
   * @throws CloudDriveException the cloud drive exception
   */
  protected CMISUser createUser(CodeAuthentication.Identity userId) throws CMISException, CloudDriveException {
    CMISAPI api = createAPI(userId);
    return new CMISUser(api.getUser(), // username as user id?
                        api.getUser(), // username as login name
                        "", // empty email
                        provider,
                        api);
  }

  /**
   * Provider id that should be used in authentication URL of the provider. In
   * case of dedicated connector based on CMIS this method may return 'cmis' id
   * to use default authentication flow for CMIS services.
   * 
   * @return {@link String}
   * @throws ConfigurationException if provider id cannot be determined for this
   *           connector authentication flow
   */
  protected String getAuthProviderId() throws ConfigurationException {
    return getProviderId();
  }

}
