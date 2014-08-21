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
import juzu.plugin.ajax.Ajax;
import juzu.template.Template;

import org.exoplatform.clouddrive.cmis.login.CodeAuthentication;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.util.Map;

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

  private static final Log                                  LOG = ExoLogger.getLogger(CMISLoginController.class);

  @Inject
  @Path("login.gtmpl")
  Template                                                  login;

  @Inject
  @Path("userkey.gtmpl")
  org.exoplatform.clouddrive.cmis.portlet.templates.userkey userKey;

  @Inject
  @Path("error.gtmpl")
  Template                                                  error;

  @Inject
  CodeAuthentication                                        authService;

  @View
  public Response index() {
    Request request = Request.getCurrent();
    Map<String, String[]> parameters = request.getParameters();
    return login.with(parameters).ok();
  }

  @View
  public Response error(String message) {
    Request request = Request.getCurrent();
    Map<String, String[]> parameters = request.getParameters();
    return error.with().set("message", message).ok();
  }

  @Ajax
  @Resource
  public Response userKey(String user) {
    // TODO transfer all parameters and redirect to redirect_url
    return userKey.with().key("$"+user+"--key").ok();
  }

  @Action
  public Response login(String user, String password) {
    // TODO transfer all parameters and redirect to redirect_url
    Request request = Request.getCurrent();
    Map<String, String[]> parameters = request.getParameters();
    String[] redirectURI = parameters.get("redirect_uri");
    if (redirectURI != null && redirectURI.length > 0) {
      return Response.redirect(redirectURI[0]);
    } else {
      // we don't have a redirect URI in the request - error
      LOG.warn("Wrong login URL: redirect_uri not found for " + user);
      // return Response.content(400, "Wrong login URL.");
      return CMISLoginController_.error("Wrong login URL.");
      // return error.with().set("message", "Wrong login URL.").ok();
    }
  }

}
