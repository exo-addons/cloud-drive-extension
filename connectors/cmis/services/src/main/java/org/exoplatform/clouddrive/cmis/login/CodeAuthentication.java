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
package org.exoplatform.clouddrive.cmis.login;

import org.exoplatform.services.idgenerator.IDGeneratorService;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.util.Random;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.RepositoryException;

/**
 * Maintain temporal codes for authentication in OAuth2 fashion. This component doesn't persist the codes.
 * Only the last attempt actual (will work for the user). <br>
 * 
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: CodeAuthentication.java 00000 Aug 19, 2014 pnedonosko $
 * 
 */
public class CodeAuthentication {

  /**
   * Lifetime of an identity in milliseconds.
   */
  public static final long   IDENTITY_LIFETIME = 1000 * 60;

  /** The Constant LOG. */
  protected static final Log LOG               = ExoLogger.getLogger(CodeAuthentication.class);

  /**
   * The Class Identity.
   */
  public class Identity {
    
    /** The user. */
    final String user;

    /** The password. */
    final String password;

    /** The service URL. */
    final String serviceURL;

    /** The service context. */
    String       serviceContext;

    /** The created. */
    final long   created;

    /**
     * Instantiates a new identity.
     *
     * @param serviceURL the service URL
     * @param user the user
     * @param password the password
     */
    Identity(String serviceURL, String user, String password) {
      super();
      this.serviceURL = serviceURL;
      this.user = user;
      this.password = password;
      this.created = System.currentTimeMillis();
    }

    /**
     * Sets the context.
     *
     * @param serviceContext the new context
     */
    void setContext(String serviceContext) {
      this.serviceContext = serviceContext;
    }

    /**
     * Gets the code source.
     *
     * @return the code source
     */
    Object getCodeSource() {
      StringBuilder src = new StringBuilder();
      src.append(user);

      int passPart = random.nextInt(password.length() - 1);
      if (passPart == 0) {
        passPart = 1;
      }
      src.append(password.substring(0, passPart));

      try {
        // we rely on JCR repo name for better uniqueness
        src.append(jcrService.getCurrentRepository().getConfiguration().getName());
      } catch (RepositoryException e) {
        LOG.warn("Error getting current JCR repository", e);
        src.append('?');
      }

      src.append(serviceURL);
      src.append(serviceContext);
      src.append(created);
      return src.toString();
    }

    /**
     * Gets the user.
     *
     * @return the user
     */
    public String getUser() {
      return user;
    }

    /**
     * Gets the password.
     *
     * @return the password
     */
    public String getPassword() {
      return password;
    }

    /**
     * Gets the service context.
     *
     * @return the serviceContext
     */
    public String getServiceContext() {
      return serviceContext;
    }

    /**
     * Sets the service context.
     *
     * @param serviceContext the serviceContext to set
     */
    public void setServiceContext(String serviceContext) {
      this.serviceContext = serviceContext;
    }

    /**
     * Gets the service URL.
     *
     * @return the serviceURL
     */
    public String getServiceURL() {
      return serviceURL;
    }

    /**
     * Gets the created.
     *
     * @return the created
     */
    public long getCreated() {
      return created;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
      return this == obj;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void finalize() throws Throwable {
      exchanged.values().remove(this); // self-cleanup
      super.finalize();
    }
  }

  /** The random. */
  private final Random                              random    = new Random();

  /** The id generator. */
  private final IDGeneratorService                  idGenerator;

  /** The jcr service. */
  private final RepositoryService                   jcrService;

  /**
   * Authentication codes before {@link #exchangeCode(String)} invocation.
   */
  private final ConcurrentHashMap<String, Identity> codes     = new ConcurrentHashMap<String, Identity>();

  /**
   * Authentication codes after {@link #exchangeCode(String)} but before
   * {@link #setCodeContext(String, String)} invocation.
   */
  private final ConcurrentHashMap<String, Identity> exchanged = new ConcurrentHashMap<String, Identity>();

  /**
   * Instantiates a new code authentication.
   *
   * @param idGenerator the id generator
   * @param jcrService the jcr service
   */
  public CodeAuthentication(IDGeneratorService idGenerator, RepositoryService jcrService) {
    this.idGenerator = idGenerator;
    this.jcrService = jcrService;
  }

  /**
   * Create user identity for given user name, password and a service URL. This identity will be stored
   * internally and an authentication code will be returned to the caller. Later this code can be exchanged on
   * the identity in {@link #exchangeCode(String)}.
   *
   * @param serviceURL {@link String}
   * @param user {@link String}
   * @param password {@link String}
   * @return {@link String}
   * @see #exchangeCode(String)
   */
  public String authenticate(String serviceURL, String user, String password) {
    Identity id = new Identity(serviceURL, user, password);
    String code = idGenerator.generateStringID(id.getCodeSource());
    Identity prevId;
    int counter = 0;
    while ((prevId = codes.putIfAbsent(code, id)) != null) {
      // such code already exists, generate a new one
      if (counter >= 1000) {
        LOG.error("Cannot find a free code for user " + user);
        throw new IllegalStateException("Code authentication not possible for the moment.");
      }
      counter++;
      if (prevId.user.equals(user)) {
        codes.remove(code, prevId);
      }
      id = new Identity(serviceURL, user, password);
      code = idGenerator.generateStringID(id.getCodeSource());
    }

    return code;
  }

  /**
   * Checks for code.
   *
   * @param code the code
   * @return true, if successful
   */
  @Deprecated
  public boolean hasCode(String code) {
    Identity id = codes.get(code);
    if (id != null && System.currentTimeMillis() - id.created < IDENTITY_LIFETIME) {
      return true;
    }
    return false;
  }

  /**
   * Exchange given code on user identity associated with this code in
   * {@link #authenticate(String, String, String)}. User identity after this method may be not fully
   * initialized as for its context. Identity context is optional and can be initialized by
   * {@link #setCodeContext(String, String)} method once, after that call identity will be fully removed from
   * the authenticator.<br>
   * If given code wasn't associated with an user previously then {@link AuthenticationException} will be
   * thrown.
   *
   * @param code {@link String}
   * @return {@link Identity} of an user
   * @throws AuthenticationException if code doesn't match any user
   * @see #setCodeContext(String, String)
   */
  public Identity exchangeCode(String code) throws AuthenticationException {
    Identity id = codes.remove(code);
    if (id != null && System.currentTimeMillis() - id.created < IDENTITY_LIFETIME) {
      exchanged.put(code, id);
      return id;
    }
    throw new AuthenticationException("Invalid code");
  }

  /**
   * Set identity context for a code. The code may be already exchanged by {@link #exchangeCode(String)},
   * after this it will be fully removed from the authenticator.<br>
   * If given code wasn't associated with an user previously then {@link AuthenticationException} will be
   * thrown.
   *
   * @param code {@link String}
   * @param context {@link String}
   * @throws AuthenticationException the authentication exception
   * @see #exchangeCode(String)
   */
  public void setCodeContext(String code, String context) throws AuthenticationException {
    Identity id = codes.get(code);
    if (id != null && System.currentTimeMillis() - id.created < IDENTITY_LIFETIME) {
      id.setContext(context);
    } else {
      id = exchanged.remove(code);
      if (id != null) {
        id.setContext(context);
      } else {
        throw new AuthenticationException("Invalid code");
      }
    }
  }

  /**
   * Checks for code context.
   *
   * @param code the code
   * @return true, if successful
   */
  @Deprecated
  public boolean hasCodeContext(String code) {
    Identity id = codes.get(code);
    if (id != null && System.currentTimeMillis() - id.created < IDENTITY_LIFETIME) {
      return id.getServiceContext() != null;
    }
    return false;
  }
}
