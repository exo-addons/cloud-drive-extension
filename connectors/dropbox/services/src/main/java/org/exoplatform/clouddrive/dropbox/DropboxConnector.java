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

import com.dropbox.core.DbxAccountInfo;
import com.dropbox.core.DbxAppInfo;
import com.dropbox.core.DbxAuthFinish;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.DbxSessionStore;
import com.dropbox.core.DbxWebAuth;
import com.dropbox.core.DbxWebAuth.BadRequestException;
import com.dropbox.core.DbxWebAuth.BadStateException;
import com.dropbox.core.DbxWebAuth.CsrfException;
import com.dropbox.core.DbxWebAuth.NotApprovedException;
import com.dropbox.core.DbxWebAuth.ProviderException;

import org.exoplatform.clouddrive.CloudDrive;
import org.exoplatform.clouddrive.CloudDriveAccessException;
import org.exoplatform.clouddrive.CloudDriveConnector;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.ConfigurationException;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudDrive;
import org.exoplatform.clouddrive.jcr.NodeFinder;
import org.exoplatform.clouddrive.utils.ExtendedMimeTypeResolver;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.services.organization.OrganizationService;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

/**
 * Cloud Drive Connector for Dropbox.<br>
 * 
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: DropboxConnector.java 00000 Aug 30, 2013 pnedonosko $
 * 
 */
public class DropboxConnector extends CloudDriveConnector {

  class AuthSessionStore implements DbxSessionStore {

    final AtomicReference<String> state = new AtomicReference<String>();

    /**
     * {@inheritDoc}
     */
    @Override
    public String get() {
      return state.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void set(String value) {
      state.set(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
      state.set(null);
    }
  }

  /**
   * Internal API builder (logic based on OAuth2 flow used in Google Drive and Box connectors).
   */
  class API {
    final DbxAppInfo        appInfo    = new DbxAppInfo(getClientId(), getClientSecret());

    final DbxRequestConfig  authConfig = new DbxRequestConfig("eXo Cloud Drive Client", Locale.getDefault().toString());

    String                  redirectUri, accessToken;

    Map<String, DbxWebAuth> auths      = new HashMap<String, DbxWebAuth>();

    Map<String, String>     authLinks  = new HashMap<String, String>();

    Map<String, String[]>   params;

    private synchronized DbxWebAuth init(String user) {
      DbxWebAuth auth = auths.get(user);
      if (auth == null) {
        auth = new DbxWebAuth(authConfig, appInfo, redirectUri, new AuthSessionStore());
        auths.put(user, auth);
      }
      return auth;
    }

    private synchronized DbxWebAuth finish(String user) {
      return auths.remove(user);
    }

    /**
     * Set redirect URI should be used for OAuth2 flow.
     * 
     * @param redirectUri String
     * @return this API
     */
    API redirectUri(String redirectUri) {
      this.redirectUri = redirectUri;
      return this;
    }

    /**
     * Return Dropbox authorization URL based on given redirect URI.
     * 
     * @param state String
     * @return String
     */
    String authLink(String state) {
      String user = currentUser();
      String link = authLinks.get(user);
      if (link == null) {
        DbxWebAuth auth = init(user);
        link = auth.start(state);
        authLinks.put(user, link);
      }
      return link;
    }

    /**
     * Authenticate to the API with OAuth2 parameters returned from redirect request.
     * 
     * @param params
     * @return this API
     */
    API auth(Map<String, String> params) {
      Map<String, String[]> dbxParams = new HashMap<String, String[]>();
      for (Map.Entry<String, String> pe : params.entrySet()) {
        dbxParams.put(pe.getKey(), new String[] { pe.getValue() });
      }
      this.params = dbxParams;
      authLinks.remove(currentUser());
      return this;
    }

    /**
     * Authenticate to the API with locally stored tokens.
     * 
     * @param accessToken
     * @return this API
     */
    API load(String accessToken) {
      init(currentUser());
      this.accessToken = accessToken;
      return this;
    }

    /**
     * Build API.
     * 
     * @return {@link DropboxAPI}
     * @throws DropboxException if error happen during communication with Google Drive services
     * @throws CloudDriveException if cannot load local tokens
     */
    DropboxAPI build() throws DropboxException, CloudDriveException {
      String user = currentUser();
      if (params != null && params.size() > 0) {
        // build API based on OAuth2 params
        try {
          DbxWebAuth auth = finish(user);
          if (auth != null) {
            DbxAuthFinish authFinish = auth.finish(params);
            return new DropboxAPI(authConfig, authFinish.accessToken);
          } else {
            throw new CloudDriveException("API not properly initialized for user " + user);
          }
        } catch (BadRequestException e) {
          String msg = "Wrong authorization parameter(s)";
          LOG.error(msg + ": " + e.getMessage(), e);
          throw new DropboxException(msg);
        } catch (BadStateException e) {
          String msg = "Authorization session expired";
          LOG.warn(msg + ": " + e.getMessage(), e);
          throw new CloudDriveAccessException(msg + ". Please try again later.");
        } catch (CsrfException e) {
          String msg = "Authorization state not found";
          LOG.warn(msg + ". CSRF error during authorization: " + e.getMessage(), e);
          throw new DropboxException(msg + ". Please retry your request.");
        } catch (NotApprovedException e) {
          String msg = "Access not approved";
          LOG.warn(msg + ": " + e.getMessage(), e);
          throw new org.exoplatform.clouddrive.NotApprovedException(msg);
        } catch (ProviderException e) {
          String msg = "Authorization process error";
          LOG.error(msg + ": " + e.getMessage(), e);
          throw new DropboxException(msg + ". Please retry your request.");
        } catch (DbxException.InvalidAccessToken e) {
          String msg = "Invalid access credentials";
          LOG.warn(msg + " (access token) : " + e.getMessage(), e);
          throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
        } catch (DbxException.RetryLater e) {
          String msg = "Dropbox overloaded or hit rate exceeded";
          LOG.warn(msg + ": " + e.getMessage(), e);
          throw new DropboxException(msg + ". Please try again later.");
        } catch (DbxException e) {
          String msg = "Dropbox error";
          LOG.error(msg + ": " + e.getMessage(), e);
          throw new DropboxException(msg + ". Please try again later.");
        }
      } else if (accessToken != null) {
        // build API based on locally stored tokens
        return new DropboxAPI(authConfig, accessToken);
      } else {
        throw new CloudDriveException("API not properly authorized nor loaded with ready access token for user " + user);
      }
    }
  }

  protected final OrganizationService organization;

  protected API                       apiBuilder;

  public DropboxConnector(RepositoryService jcrService,
                          SessionProviderService sessionProviders,
                          OrganizationService organization,
                          NodeFinder finder,
                          ExtendedMimeTypeResolver mimeTypes,
                          InitParams params) throws ConfigurationException {
    super(jcrService, sessionProviders, finder, mimeTypes, params);
    this.organization = organization;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected DropboxProvider getProvider() {
    // we cast to get an access to methods of the implementation
    return (DropboxProvider) super.getProvider();
  }

  @Override
  protected CloudProvider createProvider() throws ConfigurationException {
    // this method will be called from the constructor: need init API builder
    if (apiBuilder == null) {
      apiBuilder = new API();
    }

    String redirectURL = redirectLink();

    apiBuilder.redirectUri(redirectURL.toString());

    return new DropboxProvider(getProviderId(), getProviderName(), apiBuilder, redirectURL, jcrService);
  }

  @Override
  protected CloudUser authenticate(Map<String, String> params) throws CloudDriveException {
    String code = params.get(OAUTH2_CODE);
    if (code != null && code.length() > 0) {
      DropboxAPI driveAPI = apiBuilder.auth(params).build();
      DbxAccountInfo apiUser = driveAPI.getCurrentUser();
      String userId = String.valueOf(apiUser.userId);
      String asEmail = "<" + apiUser.displayName + ">" + userId + "@dropbox";
      DropboxUser user = new DropboxUser(userId, apiUser.displayName, asEmail, provider, driveAPI);
      return user;
    } else {
      throw new CloudDriveException("Access key should not be null or empty");
    }
  }

  @Override
  protected CloudDrive createDrive(CloudUser user, Node driveNode) throws CloudDriveException, RepositoryException {
    if (user instanceof DropboxUser) {
      DropboxUser apiUser = (DropboxUser) user;
      JCRLocalDropboxDrive drive = new JCRLocalDropboxDrive(apiUser, driveNode, sessionProviders, jcrFinder, mimeTypes);
      return drive;
    } else {
      throw new CloudDriveException("Not cloud user: " + user);
    }
  }

  @Override
  protected CloudDrive loadDrive(Node driveNode) throws DriveRemovedException, CloudDriveException, RepositoryException {
    JCRLocalCloudDrive.checkNotTrashed(driveNode);
    JCRLocalCloudDrive.migrateName(driveNode);
    JCRLocalDropboxDrive drive = new JCRLocalDropboxDrive(apiBuilder,
                                                          getProvider(),
                                                          driveNode,
                                                          sessionProviders,
                                                          jcrFinder,
                                                          mimeTypes);
    return drive;
  }

}
