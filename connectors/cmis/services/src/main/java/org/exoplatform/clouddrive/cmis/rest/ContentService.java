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
package org.exoplatform.clouddrive.cmis.rest;

import org.exoplatform.clouddrive.CloudDrive;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudDriveService;
import org.exoplatform.clouddrive.cmis.ContentReader;
import org.exoplatform.clouddrive.cmis.JCRLocalCMISDrive;
import org.exoplatform.clouddrive.features.CloudDriveFeatures;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.rest.resource.ResourceContainer;

import javax.annotation.security.RolesAllowed;
import javax.jcr.LoginException;
import javax.jcr.RepositoryException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

/**
 * RESTful service to access file content in CMIS connector operations.<br>
 * 
 */
@Path("/clouddrive/drive/cmis/content")
public class ContentService implements ResourceContainer {

  protected static final Log             LOG = ExoLogger.getLogger(ContentService.class);

  /**
   * REST service URL path.
   */
  public static String                   SERVICE_PATH;

  static {
    Path restPath = ContentService.class.getAnnotation(Path.class);
    SERVICE_PATH = restPath.value();
  }

  protected final CloudDriveFeatures     features;

  protected final CloudDriveService      cloudDrives;

  protected final RepositoryService      jcrService;

  protected final SessionProviderService sessionProviders;

  /**
   * Constructor.
   * 
   * @param cloudDrives
   * @param features
   * @param jcrService
   * @param sessionProviders
   */
  public ContentService(CloudDriveService cloudDrives,
                            CloudDriveFeatures features,
                            RepositoryService jcrService,
                            SessionProviderService sessionProviders) {
    this.cloudDrives = cloudDrives;
    this.features = features;

    this.jcrService = jcrService;
    this.sessionProviders = sessionProviders;
  }

  /**
   * Return file content reading it from cloud side.<br>
   * 
   * @param uriInfo
   * @param workspace
   * @param path
   * @param providerId
   * @return
   */
  @GET
  @RolesAllowed("users")
  public Response getFileComments(@Context UriInfo uriInfo,
                                  @QueryParam("workspace") String workspace,
                                  @QueryParam("path") String path,
                                  @QueryParam("fileId") String fileId) {
    if (workspace != null) {
      if (path != null) {
        if (fileId != null) {
          try {
            CloudDrive local = cloudDrives.findDrive(workspace, path);
            if (local != null) {
              // work with CMIS repo only
              ContentReader content = ((JCRLocalCMISDrive) local).getFileContent(fileId);
              if (content != null) {
                ResponseBuilder resp = Response.ok().entity(content.getStream());
                long len = content.getLength();
                if (len >= 0) {
                  resp.header("Content-Length", len);
                }
                String mime = content.getMimeType();
                if (mime != null && mime.length() > 0) {
                  resp.type(mime);
                }
                return resp.build();
              }
            }
            return Response.status(Status.BAD_REQUEST).entity("Not CMIS file.").build();
          } catch (LoginException e) {
            LOG.warn("Error login to read cloud file content " + workspace + ":" + path + ": "
                + e.getMessage());
            return Response.status(Status.UNAUTHORIZED).entity("Authentication error.").build();
          } catch (CloudDriveException e) {
            LOG.warn("Error reading file content " + workspace + ":" + path, e);
            return Response.status(Status.BAD_REQUEST)
                           .entity("Error reading file content. " + e.getMessage())
                           .build();
          } catch (RepositoryException e) {
            LOG.error("Error reading file content " + workspace + ":" + path, e);
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                           .entity("Error reading file content: storage error.")
                           .build();
          } catch (Throwable e) {
            LOG.error("Error reading file content " + workspace + ":" + path, e);
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                           .entity("Error reading file content: runtime error.")
                           .build();
          }
        } else {
          return Response.status(Status.BAD_REQUEST).entity("Null fileId.").build();
        }
      } else {
        return Response.status(Status.BAD_REQUEST).entity("Null path.").build();
      }
    } else {
      return Response.status(Status.BAD_REQUEST).entity("Null workspace.").build();
    }
  }
}
