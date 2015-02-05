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
import org.exoplatform.webui.config.annotation.ComponentConfig;

/**
 * Default WebUI component for CMIS cloud files. It shows content of remote file by its URL in iframe on file
 * page in eXo Documents.<br> This component uses template of DefaultCloudFileViewer from ecms module.
 * 
 * Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: DefaultFileViewer.java 00000 Jan 28, 2015 pnedonosko $
 */
@ComponentConfig(template = "classpath:groovy/templates/DefaultFileViewer.gtmpl")
public class DefaultFileViewer extends AbstractFileViewer {

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isViewable() {
    boolean res = super.isViewable();
    if (res) {
      // accept only text/* and application/xhtml+*
      String type = file.getType();
      return type.startsWith("text/") || type.startsWith("application/xhtml+");
    }
    return res;
  }
}
