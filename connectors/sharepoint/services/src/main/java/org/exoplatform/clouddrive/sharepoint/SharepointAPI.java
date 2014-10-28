/*
 * Copyright (C) 2003-2014 eXo Platform SAS.
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
package org.exoplatform.clouddrive.sharepoint;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.RefreshAccessException;
import org.exoplatform.clouddrive.cmis.CMISAPI;
import org.exoplatform.clouddrive.cmis.CMISException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.ws.frameworks.json.JsonParser;
import org.exoplatform.ws.frameworks.json.impl.JsonDefaultHandler;
import org.exoplatform.ws.frameworks.json.impl.JsonException;
import org.exoplatform.ws.frameworks.json.impl.JsonParserImpl;
import org.exoplatform.ws.frameworks.json.value.JsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStore;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * All calls to Sharepoint API here.
 * 
 */
public class SharepointAPI extends CMISAPI {

  protected static final Log    LOG              = ExoLogger.getLogger(SharepointAPI.class);

  protected static final String REST_CONTEXTINFO = "%s/_api/contextinfo";

  protected static final String REST_CURRENTUSER = "%s/_api/Web/CurrentUser";

  protected static final String REST_SITETITLE   = "$s/_api/Web/Title";

  protected class RESTClient {

    protected final HttpClient httpClient;

    protected RESTClient() {
      super();

      SchemeRegistry schemeReg = new SchemeRegistry();
      schemeReg.register(new Scheme("http", 80, PlainSocketFactory.getSocketFactory()));
      SSLSocketFactory socketFactory;
      try {
        SSLContext sslContext = SSLContext.getInstance(SSLSocketFactory.TLS);
        KeyManagerFactory kmfactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmfactory.init(null, null);
        KeyManager[] keymanagers = kmfactory.getKeyManagers();
        TrustManagerFactory tmfactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmfactory.init((KeyStore) null);
        TrustManager[] trustmanagers = tmfactory.getTrustManagers();
        sslContext.init(keymanagers, trustmanagers, null);
        socketFactory = new SSLSocketFactory(sslContext, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
      } catch (Exception ex) {
        throw new IllegalStateException("Failure initializing default SSL context for Box REST client", ex);
      }
      schemeReg.register(new Scheme("https", 443, socketFactory));

      ThreadSafeClientConnManager connectionManager = new ThreadSafeClientConnManager(schemeReg);
      // XXX 2 recommended by RFC 2616 sec 8.1.4, we make it bigger for quicker // upload
      connectionManager.setDefaultMaxPerRoute(4);
      // 20 by default, we twice it also
      connectionManager.setMaxTotal(40);

      this.httpClient = new DefaultHttpClient(connectionManager);
    }

    HttpResponse execute(HttpUriRequest request) throws ClientProtocolException, IOException {
      return this.httpClient.execute(request);
    }

    HttpResponse get(String uri) throws ClientProtocolException, IOException {
      HttpGet httpget = new HttpGet(uri);
      return execute(httpget);
    }

  }

  /**
   * SharePoint user data.
   */
  public class User {

    final String  id;

    final String  loginName;

    final String  title;

    final String  email;

    final boolean isSiteAdmin;

    User(String id, String loginName, String title, String email, boolean isSiteAdmin) {
      super();
      this.id = id;
      this.loginName = loginName;
      this.title = title;
      this.email = email;
      this.isSiteAdmin = isSiteAdmin;
    }

    /**
     * @return the id
     */
    public String getId() {
      return id;
    }

    /**
     * @return the loginName
     */
    public String getLoginName() {
      return loginName;
    }

    /**
     * @return the title
     */
    public String getTitle() {
      return title;
    }

    /**
     * @return the email
     */
    public String getEmail() {
      return email;
    }

    /**
     * @return the isSiteAdmin
     */
    public boolean getIsSiteAdmin() {
      return isSiteAdmin;
    }

  }

  /**
   * Access parameter for HTTP client to native SP APIes.
   */
  protected final String     siteURL, userName, password;

  /**
   * HTTP client to native SP APIes.
   */
  protected final RESTClient nativeClient;

  /**
   * Current SharePoint user.
   */
  protected User             siteUser;

  /**
   * SharePoint site title.
   */
  protected String           siteTitle;

  /**
   * Create API from user credentials.
   * 
   * @param serviceURL {@link String}
   * @param userName {@link String}
   * @param password {@link String}
   * @throws CMISException
   * @throws CloudDriveException
   */
  protected SharepointAPI(String serviceURL, String userName, String password) throws CMISException,
      CloudDriveException {
    super(serviceURL, userName, password);

    this.userName = userName;
    this.password = password;

    // extract site host URL from CMIS AtomPub service
    try {
      URI uri = new URI(serviceURL);
      StringBuilder host = new StringBuilder();
      host.append(uri.getScheme());
      host.append("://");
      host.append(uri.getHost());
      if (uri.getPort() != 80 || uri.getPort() != 443) {
        host.append(uri.getPort());
      }
      this.siteURL = host.toString();
    } catch (URISyntaxException e) {
      throw new CMISException("Error parsing service URL", e);
    }

    this.nativeClient = new RESTClient();
  }

  protected CmisObject getObject(String id, Session session, OperationContext context) {
    try {
      return session.getObject(id, context);
    } catch (CmisInvalidArgumentException e) {
      // try get object as a document by id with suffic -512
      try {
        return session.getObject(id + "-512", objectContext);
      } catch (CmisInvalidArgumentException e512) {
        throw e; // throw original exception
      }
    }
  }

  /**
   * {@inheritDoc}
   * 
   * @throws RefreshAccessException
   */
  @Override
  protected void initRepository(String repositoryId) throws CMISException, RefreshAccessException {
    super.initRepository(repositoryId);

    this.siteTitle = readSiteTitle();
    this.siteUser = readSiteUser();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getUserTitle() {
    if (siteUser != null) {
      return siteUser.getTitle();
    }
    return super.getUserTitle();
  }

  // ******* SharePoint specific *********

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getUser() {
    return super.getUser();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getPassword() {
    return super.getPassword();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getServiceURL() {
    return super.getServiceURL();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getRepositoryId() {
    return super.getRepositoryId();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getRepositoryName() {
    // TODO Auto-generated method stub
    return super.getRepositoryName();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected RepositoryInfo getRepositoryInfo() throws CMISException, RefreshAccessException {
    // TODO Auto-generated method stub
    return super.getRepositoryInfo();
  }

  /**
   * @return the siteUser
   */
  protected User getSiteUser() {
    return siteUser;
  }

  /**
   * @return the siteTitle
   */
  protected String getSiteTitle() {
    return siteTitle;
  }

  /**
   * @return the siteURL
   */
  protected String getSiteURL() {
    return siteURL;
  }

  /**
   * Read site name from ShapePoint API.
   * 
   * @return
   * @throws SharepointException
   */
  protected String readSiteTitle() throws SharepointException {
    String reqURI = String.format(REST_SITETITLE, siteURL);
    try {
      HttpResponse resp = nativeClient.get(reqURI);
      JsonValue json = readJson(resp.getEntity().getContent());
      JsonValue dv = json.getElement("d");
      if (dv != null && !dv.isNull()) {
        JsonValue tv = dv.getElement("Title");
        if (tv != null) {
          return tv.isNull() ? tv.toString() : tv.getStringValue();
        } else {
          throw new SharepointException("Title request doesn't return a title value");
        }
      } else {
        throw new SharepointException("Title request doesn't return an expected body (d)");
      }
    } catch (ClientProtocolException e) {
      throw new SharepointException("Protocol error reading site title", e);
    } catch (IOException e) {
      throw new SharepointException("Error reading site title", e);
    } catch (IllegalStateException e) {
      throw new SharepointException("Error fetching site title content", e);
    } catch (JsonException e) {
      throw new SharepointException("Error reading site title content", e);
    }
  }

  /**
   * Read current user name from ShapePoint API.
   * 
   * @return
   * @throws SharepointException
   */
  protected User readSiteUser() throws SharepointException {
    String reqURI = String.format(REST_CURRENTUSER, siteURL);
    try {
      HttpResponse resp = nativeClient.get(reqURI);
      JsonValue json = readJson(resp.getEntity().getContent());
      JsonValue dv = json.getElement("d");
      if (dv != null && !dv.isNull()) {
        String id, loginName, title, email;
        boolean isAdmin;

        JsonValue v = dv.getElement("Id");
        if (v != null && !v.isNull()) {
          id = v.getStringValue();
        } else {
          throw new SharepointException("Current user request doesn't return Id");
        }

        v = dv.getElement("LoginName");
        if (v != null && !v.isNull()) {
          loginName = v.getStringValue();
        } else {
          throw new SharepointException("Current user request doesn't return LoginName");
        }

        v = dv.getElement("Title");
        if (v != null && !v.isNull()) {
          title = v.getStringValue();
        } else {
          throw new SharepointException("Current user request doesn't return Title");
        }

        v = dv.getElement("Email");
        email = v == null || v.isNull() ? "" : v.getStringValue();

        v = dv.getElement("IsSiteAdmin");
        isAdmin = v == null || v.isNull() ? false : v.getBooleanValue();

        return new User(id, loginName, title, email, isAdmin);
      } else {
        throw new SharepointException("Current user request doesn't return an expected body (d)");
      }
    } catch (ClientProtocolException e) {
      throw new SharepointException("Protocol error reading curent user", e);
    } catch (IOException e) {
      throw new SharepointException("Error reading curent user", e);
    } catch (IllegalStateException e) {
      throw new SharepointException("Error fetching curent user", e);
    } catch (JsonException e) {
      throw new SharepointException("Error reading curent user data", e);
    }
  }

  protected JsonValue readJson(InputStream content) throws JsonException {
    JsonParser jsonParser = new JsonParserImpl();
    JsonDefaultHandler handler = new JsonDefaultHandler();
    jsonParser.parse(new InputStreamReader(content), handler);
    return handler.getJsonObject();
  }
}
