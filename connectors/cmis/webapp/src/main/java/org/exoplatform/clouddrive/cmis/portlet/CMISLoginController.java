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
package org.exoplatform.clouddrive.cmis.portlet;

import juzu.Action;
import juzu.Path;
import juzu.Resource;
import juzu.Response;
import juzu.View;
import juzu.impl.request.Request;

import org.exoplatform.clouddrive.CloudDriveAccessException;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudDriveService;
import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.clouddrive.ProviderNotAvailableException;
import org.exoplatform.clouddrive.cmis.CMISUser;
import org.exoplatform.clouddrive.cmis.login.AuthenticationException;
import org.exoplatform.clouddrive.cmis.login.CodeAuthentication;
import org.exoplatform.commons.juzu.ajax.Ajax;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.gatein.common.util.Base64;

import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.inject.Inject;

/**
 * Juzu controller for Cloud Drive's CMIS connector login page.<br>
 * 
 * Created by The eXo Platform SAS<br>
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: CMISLoginController.java 00000 Aug 12, 2014 pnedonosko $
 * 
 */
public class CMISLoginController {

  private static final Log                                       LOG           = ExoLogger.getLogger(CMISLoginController.class);

  private static final String                                    KEY_ALGORITHM = "RSA";

  @Inject
  @Path("login.gtmpl")
  org.exoplatform.clouddrive.cmis.portlet.templates.login        login;

  @Inject
  @Path("userkey.gtmpl")
  org.exoplatform.clouddrive.cmis.portlet.templates.userkey      userKey;

  @Inject
  @Path("repository.gtmpl")
  org.exoplatform.clouddrive.cmis.portlet.templates.repository   repository;

  @Inject
  @Path("error.gtmpl")
  org.exoplatform.clouddrive.cmis.portlet.templates.error        error;

  @Inject
  @Path("errormessage.gtmpl")
  org.exoplatform.clouddrive.cmis.portlet.templates.errormessage errorMessage;

  @Inject
  CodeAuthentication                                             authService;

  @Inject
  CloudDriveService                                              cloudDrives;

  private final ConcurrentHashMap<String, PrivateKey>            keys          = new ConcurrentHashMap<String, PrivateKey>();

  @View
  public Response index() {
    Request request = Request.getCurrent();
    Map<String, String[]> parameters = request.getParameters();
    try {
      return login.with(parameters).set("provider", cloudDrives.getProvider("cmis")).ok();
    } catch (ProviderNotAvailableException e) {
      LOG.error("Login error: provider not available", e);
      return CMISLoginController_.error("CMIS provider not available");
    }
  }

  @View
  public Response error(String message) {
    Request request = Request.getCurrent();
    Map<String, String[]> parameters = request.getParameters();
    return error.with().message(message).ok();
  }

  @Ajax
  @Resource
  public Response userKey(String user) {
    return userKey.with().key(createKey(user)).ok();
  }

  Response errorMessage(String message) {
    return errorMessage.with().message(message).ok();
  }

  @Ajax
  @Resource
  public Response loginUser(String serviceURL, String user, String password) {
    if (serviceURL != null && serviceURL.length() > 0) {
      if (user != null && user.length() > 0) {
        if (password != null && password.length() > 0) {
          try {
            String passwordText = decodePassword(user, password);
            String code = authService.authenticate(serviceURL, user, passwordText);
            CloudProvider cmisProvider = cloudDrives.getProvider("cmis");
            CMISUser cmisUser = (CMISUser) cloudDrives.authenticate(cmisProvider, code);
            return repository.with().code(code).repositories(cmisUser.getRepositories()).ok();
          } catch (InvalidKeyException e) {
            LOG.warn("Error initializing " + KEY_ALGORITHM + " cipher for key from user " + user, e);
            return errorMessage("Invalid password key of user " + user);
          } catch (IllegalBlockSizeException e) {
            LOG.warn("Error decoding " + KEY_ALGORITHM + " key from user " + user, e);
            return errorMessage("Error processing password of user " + user);
          } catch (BadPaddingException e) {
            LOG.warn("Error decoding " + KEY_ALGORITHM + " key from user " + user, e);
            return errorMessage("Error processing password of user " + user);
          } catch (IllegalStateException e) {
            LOG.error("Login error: authentication initialization error", e);
            return errorMessage("Authentication initialization error for " + user);
          } catch (ProviderNotAvailableException e) {
            LOG.error("Login error: provider not available", e);
            return errorMessage("CMIS provider not available");
          } catch (CloudDriveAccessException e) {
            LOG.warn("Login failure: " + e.getMessage());
            return errorMessage("CMIS authentication failure for " + user
                + ". Ensure you are using correct username and password and try again.");
          } catch (CloudDriveException e) {
            LOG.error("Login error: authentication error", e);
            return errorMessage("CMIS authentication error for " + user);
          }
        } else {
          LOG.warn("Wrong login: password required for " + user);
          return errorMessage("Password required");
        }
      } else {
        LOG.warn("Wrong login: user required for " + serviceURL);
        return errorMessage("User required");
      }
    } else {
      LOG.warn("Wrong login: serviceURL required");
      return errorMessage("Service URL required");
    }
  }

  @Action
  public Response loginRepository(String code, String repository) {
    Request request = Request.getCurrent();
    Map<String, String[]> parameters = request.getParameters();
    String[] redirects = parameters.get("redirect_uri");
    if (redirects != null && redirects.length > 0) {
      try {
        authService.setCodeContext(code, repository);
      } catch (AuthenticationException e) {
        LOG.warn("Authentication error. " + e.getMessage());
        return CMISLoginController_.error("Authentication error. " + e.getMessage());
      }
      String redirectURL = redirects[0];
      if (redirectURL.indexOf('?') > 0) {
        redirectURL += "&code=" + code;
      } else {
        redirectURL += "?code=" + code;
      }
      return Response.redirect(redirectURL);
    } else {
      // we don't have a redirect URI in the request - error
      LOG.warn("Wrong login URL: redirect_uri not found");
      // return Response.content(400, "Wrong login URL.");
      return CMISLoginController_.error("Wrong login URL.");
    }
  }

  // ***************** internals *****************

  /**
   * Create key-pair. Store private key in the controller. Return public key from the method (should be return
   * to an user).
   * 
   * @param user {@link String}
   * @return String public key in string encoded in Base64.
   */
  private String createKey(String user) {
    try {
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
      keyGen.initialize(1024, SecureRandom.getInstance("SHA1PRNG"));
      KeyPair keyPair = keyGen.genKeyPair();
      PublicKey publicKey = keyPair.getPublic();
      PrivateKey privateKey = keyPair.getPrivate();

      keys.put(user, privateKey);
      // TODO cleanup
      // StringBuilder retString = new StringBuilder();
      // for (int i = 0; i < key.length; ++i) {
      // retString.append(Integer.toHexString(0x0100 + (key[i] & 0x00FF)).substring(1));
      // }
      return Base64.encodeBytes(publicKey.getEncoded());
    } catch (NoSuchAlgorithmException e) {
      LOG.error("Error creating " + KEY_ALGORITHM + " key pair for user " + user, e);
      throw new IllegalStateException("Error creating key for user " + user, e);
    }
  }

  private String decodePassword(String user, String password) throws InvalidKeyException,
                                                             IllegalBlockSizeException,
                                                             BadPaddingException {
    PrivateKey userKey = keys.get(user);
    if (userKey != null) {
      try {
        Cipher cipher = Cipher.getInstance(KEY_ALGORITHM);
        // decode the plain text using the private key
        cipher.init(Cipher.DECRYPT_MODE, userKey);
        return new String(cipher.doFinal(password.getBytes()));
      } catch (NoSuchAlgorithmException e) {
        LOG.error("Error creating " + KEY_ALGORITHM + " cipher for user " + user, e);
        throw new IllegalStateException("Error decoding password for user " + user, e);
      } catch (NoSuchPaddingException e) {
        LOG.error("Error creating " + KEY_ALGORITHM + " cipher for user " + user, e);
        throw new IllegalStateException("Error decoding password for user " + user, e);
      }
    } else {
      // TODO throw new CMISLoginException("User key not found for " + user);
      LOG.warn("User key not found for " + user + ". Use password as plain text.");
      return password;
    }
  }
}
