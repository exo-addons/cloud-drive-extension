package org.exoplatform.clouddrive.cmis;

import org.exoplatform.clouddrive.CloudDrive;
import org.exoplatform.clouddrive.CloudDriveConnector;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.ConfigurationException;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudDrive;
import org.exoplatform.clouddrive.jcr.NodeFinder;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

/**
 * CMIS Connector.<br>
 * 
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: CMISConnector.java 00000 Aug 30, 2013 pnedonosko $
 * 
 */
public class CMISConnector extends CloudDriveConnector {

  /**
   * Internal API builder (logic based on OAuth2 flow used in Google Drive and Box connectors).
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
     * @return {@link CMISAPI}
     * @throws CMISException if error happen during communication with Google Drive services
     * @throws CloudDriveException if cannot load local tokens
     */
    CMISAPI build() throws CMISException, CloudDriveException {
      if (code != null && code.length() > 0) {
        // build API based on OAuth2 code
        return new CMISAPI(getClientId(), getClientSecret(), code);
      } else {
        // build API based on locally stored tokens
        return new CMISAPI(getClientId(), getClientSecret(), accessToken, refreshToken, expirationTime);
      }
    }
  }

  public CMISConnector(RepositoryService jcrService,
                       SessionProviderService sessionProviders,
                       NodeFinder finder,
                       InitParams params) throws ConfigurationException {
    super(jcrService, sessionProviders, finder, params);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CMISProvider getProvider() {
    // we cast to get an access to methods of the implementation
    return (CMISProvider) super.getProvider();
  }

  @Override
  protected CloudProvider createProvider() {
    StringBuilder redirectURL = new StringBuilder();
    redirectURL.append(getConnectorSchema());
    redirectURL.append("://");
    redirectURL.append(getConnectorHost());
    redirectURL.append("/portal/rest/clouddrive/connect/");
    redirectURL.append(getProviderId());

    // Auth URL lead to a webpage on eXo side, it should ask for username/password and store somehow on the
    // serverside for late use by the connect flow
    StringBuilder authURL = new StringBuilder();
    authURL.append(getConnectorSchema());
    authURL.append("://");
    authURL.append(getConnectorHost());
    authURL.append("/portal/clouddrive/");
    authURL.append(getProviderId());
    authURL.append("/login?redirect_uri=");
    try {
      authURL.append(URLEncoder.encode(redirectURL.toString(), "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      LOG.warn("Cannot encode redirect URL " + redirectURL.toString() + ":" + e);
      authURL.append(redirectURL);
    }

    return new CMISProvider(getProviderId(), getProviderName(), authURL.toString(), jcrService);
  }

  @Override
  protected CloudUser authenticate(String code) throws CloudDriveException {
    if (code != null && code.length() > 0) {
      CMISAPI driveAPI = new API().auth(code).build();
      Object apiUser = driveAPI.getCurrentUser();
      CMISUser user = new CMISUser("apiUser.getId()",
                                   "apiUser.getName()",
                                   "apiUser.getLogin()",
                                   provider,
                                   driveAPI);
      return user;
    } else {
      throw new CloudDriveException("Access key should not be null or empty");
    }
  }

  @Override
  protected CloudDrive createDrive(CloudUser user, Node driveNode) throws CloudDriveException,
                                                                  RepositoryException {
    if (user instanceof CMISUser) {
      CMISUser apiUser = (CMISUser) user;
      JCRLocalCMISDrive drive = new JCRLocalCMISDrive(apiUser, driveNode, sessionProviders, jcrFinder);
      return drive;
    } else {
      throw new CloudDriveException("Not cloud user: " + user);
    }
  }

  @Override
  protected CloudDrive loadDrive(Node driveNode) throws DriveRemovedException,
                                                CloudDriveException,
                                                RepositoryException {
    JCRLocalCloudDrive.checkTrashed(driveNode);
    JCRLocalCloudDrive.migrateName(driveNode);
    JCRLocalCMISDrive drive = new JCRLocalCMISDrive(new API(),
                                                    getProvider(),
                                                    driveNode,
                                                    sessionProviders,
                                                    jcrFinder);
    return drive;
  }

}
