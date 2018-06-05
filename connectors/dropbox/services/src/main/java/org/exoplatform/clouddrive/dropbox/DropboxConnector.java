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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

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
import com.dropbox.core.InvalidAccessTokenException;
import com.dropbox.core.RetryException;
import com.dropbox.core.v2.users.FullAccount;

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

/**
 * Cloud Drive Connector for Dropbox.<br>
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: DropboxConnector.java 00000 Aug 30, 2013 pnedonosko $
 */
public class DropboxConnector extends CloudDriveConnector {

  /** The Constant CLIENT_NAME. */
  public static final String CLIENT_NAME = "eXoCloudDriveClient/2.0";

  /**
   * The Class AuthSessionStore.
   */
  class AuthSessionStore implements DbxSessionStore {

    /** The value. */
    final AtomicReference<String> value = new AtomicReference<String>();

    /**
     * {@inheritDoc}
     */
    @Override
    public String get() {
      return value.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void set(String newValue) {
      value.set(newValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
      value.set(null);
    }
  }

  /**
   * The Class AuthLink.
   */
  class AuthLink {

    /** The link. */
    final String          link;

    /** The store. */
    final DbxSessionStore store;

    /**
     * Instantiates a new user link.
     *
     * @param link the link
     * @param store the store
     */
    AuthLink(String link, DbxSessionStore store) {
      super();
      this.link = link;
      this.store = store;
    }
  }

  /**
   * Dropbox API factory (logic based on OAuth2 flow used in Google Drive and
   * Box connectors).
   */
  class API {

    class Builder {

      /** Dropbox authenticator. */
      final DbxWebAuth      auth         = new DbxWebAuth(authConfig, appInfo);

      /** The session store. */
      DbxSessionStore       sessionStore = new AuthSessionStore();

      /** The access token. */
      String                accessToken;

      /** The state associated with an user. */
      String                state;

      /** The authentication link. */
      String                authLink;

      /** The auth request params. */
      Map<String, String[]> params;

      /**
       * Add access token.
       *
       * @param accessToken the access token
       * @return the builder
       */
      Builder withAccessToken(String accessToken) {
        this.accessToken = accessToken;
        return this;
      }

      /**
       * Add state associated with API and user.
       *
       * @param state the state
       * @return the builder
       */
      Builder withState(String state) {
        if (this.state == null || (this.state != null && !this.state.equals(state))) {
          this.state = state;
          this.authLink = null;
        }
        return this;
      }

      /**
       * Add auth params.
       *
       * @param params the params
       * @return the builder
       */
      Builder withParams(Map<String, String[]> params) {
        this.params = params;
        return this;
      }

      /**
       * Builds authentication link (if not already built).
       *
       * @return the string
       */
      String buildAuthLink() {
        if (authLink == null) {
          DbxWebAuth.Request.Builder authRequestBuilder = DbxWebAuth.newRequestBuilder()
                                                                    // After we
                                                                    // redirect
                                                                    // the user
                                                                    // to the
                                                                    // Dropbox
                                                                    // website
                                                                    // for
                                                                    // authorization,
                                                                    // Dropbox
                                                                    // will
                                                                    // redirect
                                                                    // them back
                                                                    // here.
                                                                    .withRedirectUri(redirectUri, sessionStore);

          if (state != null) {
            authRequestBuilder.withState(state);
          }

          this.authLink = auth.authorize(authRequestBuilder.build());
        }
        return authLink;
      }

      /**
       * Build API.
       * 
       * @return {@link DropboxAPI}
       * @throws DropboxException if error happen during communication with
       *           Google Drive services
       * @throws CloudDriveException if cannot load local tokens
       */
      DropboxAPI build() throws DropboxException, CloudDriveException {
        String user = currentUser();
        if (params != null && params.size() > 0) {
          // build API based on OAuth2 params
          try {
            DbxAuthFinish authFinish = auth.finishFromRedirect(redirectUri, sessionStore, params);
            return new DropboxAPI(authConfig, authFinish.getAccessToken());
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
          } catch (InvalidAccessTokenException e) {
            String msg = "Invalid access credentials";
            LOG.warn(msg + " (access token) : " + e.getMessage(), e);
            throw new RefreshAccessException(msg + ". Please authorize to Dropbox.");
          } catch (RetryException e) {
            String msg = "Dropbox overloaded or hit rate exceeded";
            LOG.warn(msg + ": " + e.getMessage(), e);
            throw new DropboxException(msg + ". Please try again later.");
          } catch (DbxException e) {
            String msg = "Dropbox error";
            LOG.error(msg + ": " + e.getMessage(), e);
            throw new DropboxException(msg + ". Please try again later.");
          } finally {
            params.clear();
            sessionStore.clear();
            accessToken = null;
            API.this.finish(user);
          }
        } else if (accessToken != null) {
          // build API based on locally stored tokens
          return new DropboxAPI(authConfig, accessToken);
        } else {
          throw new CloudDriveException("API not properly authorized nor loaded with ready access token for user " + user);
        }
      }
    }

    /** Dropbox app info shared among all users. */
    final DbxAppInfo           appInfo     = new DbxAppInfo(getClientId(), getClientSecret());

    /** Login redirect link. */
    final String               redirectUri = redirectLink();

    /** Auth config for all users. */
    // FYI we use system locale for an user as its messages will (also) go to
    // the server log.
    final DbxRequestConfig     authConfig  = DbxRequestConfig.newBuilder(CLIENT_NAME)
                                                             .withUserLocaleFrom(Locale.getDefault())
                                                             .build();

    /** API builders in per-user map. */
    final Map<String, Builder> users       = new HashMap<String, Builder>();

    /**
     * Inits per-user API builder.
     *
     * @param user the user
     * @return the builder for given user
     */
    private synchronized API.Builder init(String user) {
      Builder apiBuilder = users.get(user);
      if (apiBuilder == null) {
        apiBuilder = new Builder();
        users.put(user, apiBuilder);
      }
      return apiBuilder;
    }

    /**
     * Get and finish API builder initialization.
     *
     * @param user the user
     * @return the builder for given user
     */
    private synchronized API.Builder finish(String user) {
      return users.remove(user);
    }

    /**
     * Return Dropbox authorization URL based on given redirect URI.
     * 
     * @param state String
     * @return String
     */
    String authLink(String state) {
      return init(currentUser()).withState(state).buildAuthLink();
    }

    /**
     * Authenticate to the API with OAuth2 parameters returned from redirect
     * request.
     *
     * @param params the auth request params
     * @return a {@link Builder} for Dropbox API
     */
    API.Builder auth(Map<String, String> params) {
      Map<String, String[]> dbxParams = new HashMap<String, String[]>();
      for (Map.Entry<String, String> pe : params.entrySet()) {
        dbxParams.put(pe.getKey(), new String[] { pe.getValue() });
      }
      return init(currentUser()).withParams(dbxParams);
    }

    /**
     * Authenticate to the API with locally stored tokens.
     *
     * @param accessToken the access token
     * @return a {@link Builder} for Dropbox API
     */
    API.Builder load(String accessToken) {
      return init(currentUser()).withAccessToken(accessToken);
    }
  }

  /** The organization. */
  protected final OrganizationService organization;

  /** Dropbox API factory instance. */
  private API                         apiFactory;

  /**
   * Instantiates a new Dropbox connector.
   *
   * @param jcrService the jcr service
   * @param sessionProviders the session providers
   * @param organization the organization
   * @param finder the finder
   * @param mimeTypes the mime types
   * @param params the params
   * @throws ConfigurationException the configuration exception
   */
  public DropboxConnector(RepositoryService jcrService,
                          SessionProviderService sessionProviders,
                          OrganizationService organization,
                          NodeFinder finder,
                          ExtendedMimeTypeResolver mimeTypes,
                          InitParams params)
      throws ConfigurationException {
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

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudProvider createProvider() throws ConfigurationException {
    return new DropboxProvider(getProviderId(), getProviderName(), apiFactory(), jcrService);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudUser authenticate(Map<String, String> params) throws CloudDriveException {
    String code = params.get(OAUTH2_CODE);
    if (code != null && code.length() > 0) {
      DropboxAPI driveAPI = apiFactory().auth(params).build();
      FullAccount apiUser = driveAPI.getCurrentUser();
      DropboxUser user = new DropboxUser(apiUser.getAccountId(),
                                         apiUser.getName().getDisplayName(),
                                         apiUser.getEmail(),
                                         provider,
                                         driveAPI);
      return user;
    } else {
      throw new CloudDriveException("Access key should not be null or empty");
    }
  }

  /**
   * {@inheritDoc}
   */
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

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudDrive loadDrive(Node driveNode) throws DriveRemovedException, CloudDriveException, RepositoryException {
    JCRLocalCloudDrive.checkNotTrashed(driveNode);
    JCRLocalCloudDrive.migrateName(driveNode);
    JCRLocalDropboxDrive drive = new JCRLocalDropboxDrive(apiFactory(),
                                                          getProvider(),
                                                          driveNode,
                                                          sessionProviders,
                                                          jcrFinder,
                                                          mimeTypes);
    return drive;
  }

  /**
   * On-demand creator of API factory.
   *
   * @return the API factory.
   */
  protected API apiFactory() {
    if (apiFactory == null) {
      apiFactory = new API();
    }
    return apiFactory;
  }
}
