package org.exoplatform.clouddrive.box;

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
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

/**
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: BoxConnector.java 00000 Aug 30, 2013 pnedonosko $
 * 
 */
public class BoxConnector extends CloudDriveConnector {

  /**
   * Partner's PartnerIdpId used by Box SSO support. It will be used to construct the SSO auth link.
   */
  public static final String CONFIG_LOGIN_SSO_PARTNERIDPID = "box-sso-partneridpid";

  /**
   * Full URL for Box SSO support without redirect URL at the end. This URL will be used to construct auth
   * link by adding encoded redirect URL at the end. If this parameter present in the configuration it will be
   * preferred to use instead of constructing from {@link #CONFIG_LOGIN_SSO_PARTNERIDPID}.
   */
  public static final String CONFIG_LOGIN_SSO_URL          = "box-sso-url";

  /**
   * Box API builder (code grabbed from GoogleDriveConnector, 30 Aug 2013).
   */
  class API {
    String code, refreshToken, accessToken;

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
     * @param refreshToken
     * @param accessToken
     * @param expirationTime
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
     * @return {@link BoxAPI}
     * @throws BoxException if error happen during communication with Google Drive services
     * @throws CloudDriveException if cannot load local tokens
     */
    BoxAPI build() throws BoxException, CloudDriveException {
      if (code != null && code.length() > 0) {
        // build API based on OAuth2 code
        return new BoxAPI(getClientId(), getClientSecret(), code, getProvider().getRedirectURL());
      } else {
        // build API based on locally stored tokens
        return new BoxAPI(getClientId(), getClientSecret(), accessToken, refreshToken, expirationTime);
      }
    }
  }

  public BoxConnector(RepositoryService jcrService,
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
  protected BoxProvider getProvider() {
    return (BoxProvider) super.getProvider();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getConnectorHost() {
    if (getConnectorSchema().equalsIgnoreCase("https")) {
      return super.getConnectorHost();
    } else {
      // if not HTTPS, then only localhost possible
      String[] host = super.getConnectorHost().split(":");
      StringBuilder newHost = new StringBuilder();
      newHost.append("localhost");
      if (host.length > 1) {
        // but use original port
        newHost.append(':');
        newHost.append(host[1]);
      }
      LOG.warn("Box connector supports only HTTPS for server redirect. Switched to localhost (Box's Development Mode).");
      return newHost.toString();
    }
  }

  @Override
  protected CloudProvider createProvider() {
    StringBuilder redirectURL = new StringBuilder();
    redirectURL.append(getConnectorSchema());
    redirectURL.append("://");
    redirectURL.append(getConnectorHost());
    redirectURL.append('/');
    redirectURL.append(PortalContainer.getCurrentPortalContainerName());
    redirectURL.append('/');
    redirectURL.append(PortalContainer.getCurrentRestContextName());
    redirectURL.append("/clouddrive/connect/");
    redirectURL.append(getProviderId());

    StringBuilder oauthURL = new StringBuilder();
    oauthURL.append("https://");
    if (loginSSO) {
      oauthURL.append("app"); // it is how SSO will work only!
    } else {
      oauthURL.append("www"); // we follow the doc here
    }
    oauthURL.append(".box.com/api/oauth2/authorize?");
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
      oauthURL.append(URLEncoder.encode(BoxAPI.NO_STATE, "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      LOG.warn("Cannot encode state " + BoxAPI.NO_STATE + ":" + e);
      oauthURL.append(BoxAPI.NO_STATE);
    }
    oauthURL.append("&redirect_uri=");
    // actual uri will be appended below to avoid double encoding in case of SSO

    StringBuilder authURL = new StringBuilder();
    if (loginSSO) {
      // if SSO enabled we construct special URL
      String ssoURL = config.get(CONFIG_LOGIN_SSO_URL);
      if (ssoURL != null && (ssoURL = ssoURL.trim()).length() > 0) {
        // use SSO URL provided by configuration
        authURL.append(ssoURL);
      } else {
        String ssoPartnerIdpId = config.get(CONFIG_LOGIN_SSO_PARTNERIDPID);
        if (ssoPartnerIdpId != null && (ssoPartnerIdpId = ssoPartnerIdpId.trim()).length() > 0) {
          // Construct SSO login URL
          authURL.append("https://sso.services.box.net/sp/startSSO.ping?PartnerIdpId=");
          authURL.append(ssoPartnerIdpId);
          authURL.append("&TargetResource="); // actual target will be appended below
        } else {
          LOG.warn("SSO enabled but " + CONFIG_LOGIN_SSO_PARTNERIDPID
              + " not configured. SSO will not be forced for Box connect.");
        }
      }
      oauthURL.append(redirectURL);
      try {
        authURL.append(URLEncoder.encode(oauthURL.toString(), "UTF-8"));
      } catch (UnsupportedEncodingException e) {
        LOG.warn("Cannot encode auth URL " + oauthURL.toString() + ":" + e);
        authURL.append(oauthURL);
      }
    } else {
      try {
        oauthURL.append(URLEncoder.encode(redirectURL.toString(), "UTF-8"));
      } catch (UnsupportedEncodingException e) {
        LOG.warn("Cannot encode redirect URL " + redirectURL.toString() + ":" + e);
        oauthURL.append(redirectURL);
      }
      authURL.append(oauthURL);
    }

    return new BoxProvider(getProviderId(),
                           getProviderName(),
                           authURL.toString(),
                           redirectURL.toString(),
                           loginSSO,
                           jcrService);
  }

  @Override
  protected CloudUser authenticate(String code) throws CloudDriveException {
    if (code != null && code.length() > 0) {
      BoxAPI driveAPI = new API().auth(code).build();
      com.box.boxjavalibv2.dao.BoxUser buser = driveAPI.getCurrentUser();
      BoxUser user = new BoxUser(buser.getId(), buser.getName(), buser.getLogin(), provider, driveAPI);
      return user;
    } else {
      throw new CloudDriveException("Access key should not be null or empty");
    }
  }

  @Override
  protected CloudDrive createDrive(CloudUser user, Node driveNode) throws CloudDriveException,
                                                                  RepositoryException {
    if (user instanceof BoxUser) {
      BoxUser boxUser = (BoxUser) user;
      JCRLocalBoxDrive drive = new JCRLocalBoxDrive(boxUser,
                                                    driveNode,
                                                    sessionProviders,
                                                    jcrFinder,
                                                    mimeTypes);
      return drive;
    } else {
      throw new CloudDriveException("Not Box user: " + user);
    }
  }

  @Override
  protected CloudDrive loadDrive(Node driveNode) throws DriveRemovedException,
                                                CloudDriveException,
                                                RepositoryException {
    JCRLocalCloudDrive.checkNotTrashed(driveNode);
    JCRLocalCloudDrive.migrateName(driveNode);
    JCRLocalBoxDrive drive = new JCRLocalBoxDrive(new API(),
                                                  getProvider(),
                                                  driveNode,
                                                  sessionProviders,
                                                  jcrFinder,
                                                  mimeTypes);
    return drive;
  }

}
