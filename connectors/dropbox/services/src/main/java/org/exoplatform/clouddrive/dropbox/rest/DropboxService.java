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
package org.exoplatform.clouddrive.dropbox.rest;

import org.exoplatform.clouddrive.CloudDrive;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudDriveService;
import org.exoplatform.clouddrive.CloudFile;
import org.exoplatform.clouddrive.NotCloudFileException;
import org.exoplatform.clouddrive.features.CloudDriveFeatures;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.rest.resource.ResourceContainer;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.jcr.LoginException;
import javax.jcr.RepositoryException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

/**
 * Sample RESTful service to provide some specific features of Dropbox connector.<br>
 * 
 */
@Path("/clouddrive/dropbox")
@Produces(MediaType.APPLICATION_JSON)
public class DropboxService implements ResourceContainer {

  /** The Constant LOG. */
  protected static final Log             LOG = ExoLogger.getLogger(DropboxService.class);

  /** The features. */
  protected final CloudDriveFeatures     features;

  /** The cloud drives. */
  protected final CloudDriveService      cloudDrives;

  /** The jcr service. */
  protected final RepositoryService      jcrService;

  /** The session providers. */
  protected final SessionProviderService sessionProviders;

  /**
   * Constructor.
   *
   * @param cloudDrives the cloud drives
   * @param features the features
   * @param jcrService the jcr service
   * @param sessionProviders the session providers
   */
  public DropboxService(CloudDriveService cloudDrives,
                         CloudDriveFeatures features,
                         RepositoryService jcrService,
                         SessionProviderService sessionProviders) {
    this.cloudDrives = cloudDrives;
    this.features = features;

    this.jcrService = jcrService;
    this.sessionProviders = sessionProviders;
  }

  /**
   * Return content of a file existing on cloud side.<br>
   *
   * @param uriInfo the uri info
   * @param workspace the workspace
   * @param path the path
   * @param contentId the content id
   * @return the file link
   */
  @GET
  @Path("/content")
  @RolesAllowed("users")
  public Response getFileLink(@Context UriInfo uriInfo,
                                  @QueryParam("workspace") String workspace,
                                  @QueryParam("path") String path, 
                                  @QueryParam("contentId") String contentId) {
    if (workspace != null) {
      if (path != null) {
        // TODO Get a cloud file and return collection of comments on the file.
        try {
          CloudDrive local = cloudDrives.findDrive(workspace, path);
          if (local != null) {
            List<Object> comments = new ArrayList<Object>();
            try {
              CloudFile file = local.getFile(path);
              // TODO fill collection of comments on the file.
              comments.add(new Object());
            } catch (NotCloudFileException e) {
              // we assume: not yet uploaded file has no remote comments
            }
            return Response.ok().entity(comments).build();
          }
          return Response.status(Status.NO_CONTENT).build();
        } catch (LoginException e) {
          LOG.warn("Error login to read drive file comments " + workspace + ":" + path + ": "
              + e.getMessage());
          return Response.status(Status.UNAUTHORIZED).entity("Authentication error.").build();
        } catch (CloudDriveException e) {
          LOG.warn("Error reading file comments " + workspace + ":" + path, e);
          return Response.status(Status.BAD_REQUEST)
                         .entity("Error reading file comments. " + e.getMessage())
                         .build();
        } catch (RepositoryException e) {
          LOG.error("Error reading file comments " + workspace + ":" + path, e);
          return Response.status(Status.INTERNAL_SERVER_ERROR)
                         .entity("Error reading file comments: storage error.")
                         .build();
        } catch (Throwable e) {
          LOG.error("Error reading file comments " + workspace + ":" + path, e);
          return Response.status(Status.INTERNAL_SERVER_ERROR)
                         .entity("Error reading file comments: runtime error.")
                         .build();
        }
      } else {
        return Response.status(Status.BAD_REQUEST).entity("Null path.").build();
      }
    } else {
      return Response.status(Status.BAD_REQUEST).entity("Null workspace.").build();
    }
  }
}
