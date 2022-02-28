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
package org.exoplatform.clouddrive.cmis;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.jcr.RepositoryException;

import org.exoplatform.services.cms.clouddrives.CloudDriveConnector.PredefinedServices;
import org.exoplatform.services.cms.clouddrives.CloudDriveException;
import org.exoplatform.services.cms.clouddrives.CloudProvider;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * CMIS provider.
 */
public class CMISProvider extends CloudProvider {

  /**
   * The Class AtomPub.
   */
  public static class AtomPub {

    /** The name. */
    String name;

    /** The url. */
    String url;

    /** The hash code. */
    int    hashCode;

    /**
     * Sets the name.
     *
     * @param name the name to set
     */
    public void setName(String name) {
      this.hashCode = 0;
      this.name = name;
    }

    /**
     * Sets the url.
     *
     * @param url the url to set
     */
    public void setUrl(String url) {
      this.hashCode = 0;
      this.url = url;
    }

    /**
     * Gets the name.
     *
     * @return the name
     */
    public String getName() {
      return name;
    }

    /**
     * Gets the url.
     *
     * @return the url
     */
    public String getUrl() {
      return url;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
      if (hashCode == 0 && url != null) {
        int hc = 1;
        if (name != null) {
          hc = 17 + name.hashCode();
        } else {
          hc = 19;
        }
        hc = hc * 31 + url.hashCode();
        hashCode = hc;
      }

      return hashCode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
      if (obj instanceof AtomPub) {
        return (name != null ? name.equals(((AtomPub) obj).getName()) : true) && (url.equals(((AtomPub) obj).getUrl()));
      }
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      return name != null ? name + ": " + url : url;
    }
  }

  /** The Constant LOG. */
  protected static final Log        LOG        = ExoLogger.getLogger(CMISProvider.class);

  /** The auth URL. */
  protected final String            authURL;

  /** The jcr service. */
  protected final RepositoryService jcrService;

  /** The predefined. */
  protected Set<AtomPub>            predefined = new LinkedHashSet<AtomPub>();

  /**
   * Instantiates a new CMIS provider.
   *
   * @param id the id
   * @param name the name
   * @param authURL the auth URL
   * @param jcrService the jcr service
   */
  public CMISProvider(String id, String name, String authURL, RepositoryService jcrService) {
    super(id, name);
    this.authURL = authURL;
    this.jcrService = jcrService;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getAuthURL() throws CloudDriveException {
    if (jcrService != null) {
      try {
        String currentRepo = jcrService.getCurrentRepository().getConfiguration().getName();
        return authURL.replace(CloudProvider.AUTH_NOSTATE, currentRepo);
      } catch (RepositoryException e) {
        throw new CloudDriveException(e);
      }
    } else {
      return authURL;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean retryOnProviderError() {
    // repeat on error
    return true;
  }

  /**
   * Gets the predefined atompub services.
   *
   * @return the predefined atompub services
   */
  public Set<AtomPub> getPredefinedAtompubServices() {
    return Collections.unmodifiableSet(predefined);
  }

  /**
   * Inits the predefined.
   *
   * @param predefined the predefined
   */
  protected void initPredefined(PredefinedServices predefined) {
    String propKey = "clouddrive." + getId() + ".predefined";
    String predefinedPropOverride = System.getProperty(propKey + ".override", "true");

    if ("true".equalsIgnoreCase(predefinedPropOverride)) {
      // get predefined services from connector plugin configuration (in
      // container configuration)
      for (Object obj : predefined.getServices()) {
        if (obj instanceof AtomPub) {
          this.predefined.add((AtomPub) obj);
        } else {
          LOG.warn("Not supported predefined service: " + predefined.getClass().getName());
        }
      }
    }

    // add predefined services from system properties (set via exo.properties or
    // directly in JVM)
    String predefinedProp = System.getProperty(propKey);
    if (predefinedProp != null) {
      // parse predefined string
      for (String ps : predefinedProp.split("\n")) {
        int i = ps.indexOf(":");
        if (i + 1 < ps.length()) {
          AtomPub p = new AtomPub();
          p.setName(ps.substring(0, i));
          p.setUrl(ps.substring(i + 1));
          this.predefined.add(p);
        } else {
          LOG.warn("Cannot load predefined service from property: " + ps);
        }
      }
    }
  }
}
