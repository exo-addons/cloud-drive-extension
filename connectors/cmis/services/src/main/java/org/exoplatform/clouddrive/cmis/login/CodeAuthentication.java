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
package org.exoplatform.clouddrive.cmis.login;

import org.exoplatform.services.idgenerator.IDGeneratorService;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.util.Random;
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
  public static final long   IDENTITY_LIFETIME = 1000 * 10;

  protected static final Log LOG               = ExoLogger.getLogger(CodeAuthentication.class);

  class Identity {
    final String user;

    final String password;

    final long   created;

    Identity(String user, String password) {
      super();
      this.user = user;
      this.password = password;
      this.created = System.currentTimeMillis();
    }

    Object getCodeSource() {
      int passPart = random.nextInt(password.length() - 1);
      if (passPart == 0) {
        passPart = 1;
      }
      String jcrName;
      try {
        // we rely on JCR repo name for better uniqueness
        jcrName = jcrService.getCurrentRepository().getConfiguration().getName();
      } catch (RepositoryException e) {
        LOG.warn("Error getting current JCR repository", e);
        jcrName = "?";
      }
      return user + password.substring(0, passPart) + jcrName + created;
    }
  }

  private final Random                              random     = new Random();

  private final IDGeneratorService                  idGenerator;

  private final RepositoryService                   jcrService;

  private final ConcurrentHashMap<String, Identity> identities = new ConcurrentHashMap<String, Identity>();

  /**
   * 
   */
  public CodeAuthentication(IDGeneratorService idGenerator, RepositoryService jcrService) {
    this.idGenerator = idGenerator;
    this.jcrService = jcrService;
  }

  public String authenticate(String user, String password) {
    Identity id = new Identity(user, password);
    String code = idGenerator.generateStringID(id.getCodeSource());
    Identity prevId;
    int counter = 0;
    while ((prevId = identities.putIfAbsent(code, id)) != null) {
      // such code already exists, generate a new one
      if (counter >= 1000) {
        LOG.error("Cannot find a free code for user " + user);
        throw new IllegalStateException("Code authentication not possible for the moment.");
      }
      counter++;
      if (prevId.user.equals(user)) {
        identities.remove(code, prevId);
      }
      id = new Identity(user, password);
      code = idGenerator.generateStringID(id.getCodeSource());
    }

    // TODO invoke outdated codes cleanup
    return code;
  }

  public boolean hasCode(String code) {
    return identities.containsKey(code);
  }

  public Identity exchangeCode(String code) {
    Identity id = identities.remove(code);
    if (System.currentTimeMillis() - id.created < IDENTITY_LIFETIME) {
      return id;
    } else {
      return null;
    }
  }

}
