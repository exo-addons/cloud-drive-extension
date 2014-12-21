package org.exoplatform.clouddrive.sharepoint;

import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.exoplatform.clouddrive.CloudDrive;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudDriveService;
import org.exoplatform.clouddrive.CloudUser;
import org.exoplatform.clouddrive.ConfigurationException;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.ProviderNotAvailableException;
import org.exoplatform.clouddrive.cmis.CMISAPI;
import org.exoplatform.clouddrive.cmis.CMISConnector;
import org.exoplatform.clouddrive.cmis.CMISConnectorImpl;
import org.exoplatform.clouddrive.cmis.CMISException;
import org.exoplatform.clouddrive.cmis.JCRLocalCMISDrive;
import org.exoplatform.clouddrive.cmis.login.CodeAuthentication;
import org.exoplatform.clouddrive.cmis.login.CodeAuthentication.Identity;
import org.exoplatform.clouddrive.jcr.NodeFinder;
import org.exoplatform.clouddrive.sharepoint.SharepointAPI.User;
import org.exoplatform.clouddrive.utils.ExtendedMimeTypeResolver;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

/**
 * Sharepoint Connector.<br>
 * 
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: SharepointConnector.java 00000 Aug 30, 2013 pnedonosko $
 * 
 */
public class SharepointConnector extends CMISConnector implements CMISConnectorImpl {

  public static final String SP_VENDORNAME     = "Microsoft Corporation";

  public static final String SP_PRODUCTNAME    = "Office SharePoint Server";

  public static final String SP_AUTHPROVIDERID = "cmis";

  /**
   * Internal API builder.
   */
  protected class API extends org.exoplatform.clouddrive.cmis.CMISConnector.API {

    /**
     * {@inheritDoc}
     */
    @Override
    protected API auth(String user, String password) {
      return (API) super.auth(user, password);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected API serviceUrl(String serviceUrl) {
      return (API) super.serviceUrl(serviceUrl);
    }

    /**
     * Build API.
     * 
     * @return {@link SharepointAPI}
     * @throws CMISException if error happen during communication with SharePoint services
     * @throws CloudDriveException if cannot load local tokens
     */
    protected SharepointAPI build() throws CMISException, CloudDriveException {
      if (user == null || password == null) {
        throw new CloudDriveException("Cannot create API: user required");
      }
      if (serviceUrl == null) {
        throw new CloudDriveException("Cannot create API: service URL required");
      }
      return new SharepointAPI(serviceUrl, user, password);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected SharepointUser createUser(String userId, String userName, String email, CMISAPI api) {
      return new SharepointUser(userId, userName, email, getProvider(), (SharepointAPI) api);
    }
  }

  public SharepointConnector(RepositoryService jcrService,
                             SessionProviderService sessionProviders,
                             NodeFinder finder,
                             ExtendedMimeTypeResolver mimeTypes,
                             InitParams params,
                             CodeAuthentication codeAuth) throws ConfigurationException {
    super(jcrService, sessionProviders, finder, mimeTypes, params, codeAuth);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean hasSupport(RepositoryInfo repo) {
    if (repo.getVendorName().equals(SP_VENDORNAME)) {
      if (repo.getProductName().equals(SP_PRODUCTNAME)) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SharepointConnector getConnector() {
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected SharepointAPI createAPI(Identity userId) throws CMISException, CloudDriveException {
    return new API().auth(userId.getUser(), userId.getPassword()).serviceUrl(userId.getServiceURL()).build();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected SharepointUser createUser(Identity userId) throws CMISException, CloudDriveException {
    SharepointAPI spAPI = createAPI(userId);
    User user = spAPI.getSiteUser();
    return new SharepointUser(user.getId(), // id
                              spAPI.getUser(), // username used to connect the service
                              user.getEmail(), // email
                              provider,
                              spAPI);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudDrive createDrive(CloudUser user, Node driveNode) throws CloudDriveException,
                                                                  RepositoryException {
    if (user instanceof SharepointUser) {
      SharepointUser apiUser = (SharepointUser) user;
      JCRLocalCMISDrive drive = new JCRLocalSharepointDrive(apiUser,
                                                            driveNode,
                                                            sessionProviders,
                                                            jcrFinder,
                                                            mimeTypes,
                                                            exoURL());
      return drive;
    } else {
      throw new CloudDriveException("Not SharePoint user: " + user);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected CloudDrive loadDrive(Node driveNode) throws DriveRemovedException,
                                                CloudDriveException,
                                                RepositoryException {
    JCRLocalSharepointDrive.checkNotTrashed(driveNode);
    JCRLocalSharepointDrive.migrateName(driveNode);
    JCRLocalSharepointDrive drive = new JCRLocalSharepointDrive(new API(),
                                                                driveNode,
                                                                sessionProviders,
                                                                jcrFinder,
                                                                mimeTypes,
                                                                exoURL());
    return drive;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getAuthProviderId() throws ConfigurationException {
    CloudDriveService cdService = (CloudDriveService) ExoContainerContext.getCurrentContainer()
                                                                         .getComponentInstanceOfType(CloudDriveService.class);
    try {
      return cdService.getProvider(SP_AUTHPROVIDERID).getId(); // CMIS provider id hardcoded
    } catch (ProviderNotAvailableException e) {
      throw new ConfigurationException("Cannot initialize " + SP_PRODUCTNAME + " connector: "
          + SP_AUTHPROVIDERID + " provider not registered but required", e);
    }
  }
}
