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

import juzu.Path;
import juzu.Response;
import juzu.View;
import juzu.template.Template;

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

  @Inject
  @Path("login.gtmpl")
  Template login;

  @View
  public Response index() {
    return login.ok();
  }

}
