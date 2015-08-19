/*
 * Copyright (C) 2003-2012 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.clouddrive.cmis.ecms.viewer;

import org.exoplatform.clouddrive.ecms.viewer.AbstractFileViewer;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.webui.config.annotation.ComponentConfig;

/**
 * Default WebUI component for CMIS files. It accepts only visual file formats and show the content of remote
 * file by its URL in iframe on file page in eXo Documents.<br>
 * 
 * Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: DefaultFileViewer.java 00000 Aug 14, 2015 pnedonosko $
 */
@ComponentConfig(template = "classpath:groovy/templates/DefaultFileViewer.gtmpl")
public class DefaultFileViewer extends AbstractFileViewer {

  protected static final Log LOG = ExoLogger.getLogger(DefaultFileViewer.class);

  public DefaultFileViewer() {
    super();
  }

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
