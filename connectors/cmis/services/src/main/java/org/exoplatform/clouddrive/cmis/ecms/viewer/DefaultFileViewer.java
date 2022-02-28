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
package org.exoplatform.clouddrive.cmis.ecms.viewer;

import org.exoplatform.ecm.webui.viewer.AbstractCloudFileViewer;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.webui.config.annotation.ComponentConfig;

/**
 * Default WebUI component for CMIS files. It accepts only visual file formats
 * and show the content of remote file by its URL in iframe on file page in eXo
 * Documents.<br>
 * Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: DefaultFileViewer.java 00000 Aug 14, 2015 pnedonosko $
 */
@ComponentConfig(template = "classpath:groovy/templates/DefaultFileViewer.gtmpl")
public class DefaultFileViewer extends AbstractCloudFileViewer {

  /** The Constant LOG. */
  protected static final Log LOG = ExoLogger.getLogger(DefaultFileViewer.class);

  /**
   * Instantiates a new default file viewer.
   */
  public DefaultFileViewer() {
    super();
  }

  /**
   * Instantiates a new default file viewer.
   *
   * @param viewableMaxSize the viewable max size
   */
  protected DefaultFileViewer(long viewableMaxSize) {
    super(viewableMaxSize);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isViewable() {
    if (super.isViewable()) {
      String mimeType = file.getType();
      return mimeType.startsWith("text/") || mimeType.startsWith("image/") || mimeType.startsWith("application/xhtml+");
    }
    return false;
  }
}
