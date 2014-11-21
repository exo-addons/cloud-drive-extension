/*
 * Copyright (C) 2003-2008 eXo Platform SAS.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see<http://www.gnu.org/licenses/>.
 */
package org.exoplatform.clouddrive.cmis.ecms.viewer;

import org.exoplatform.clouddrive.CloudDrive;
import org.exoplatform.clouddrive.CloudFile;
import org.exoplatform.clouddrive.ecms.BaseCloudDriveManagerComponent;
import org.exoplatform.web.application.Parameter;
import org.exoplatform.webui.application.WebuiRequestContext;
import org.exoplatform.webui.config.annotation.ComponentConfig;

/**
 *
 */
@ComponentConfig(template = "classpath:groovy/templates/TextViewer.gtmpl")
public class TextViewer extends BaseCloudDriveManagerComponent implements CloudFileViewer {

  protected CloudDrive drive;

  protected CloudFile  file;

  public TextViewer() throws Exception {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void processRender(WebuiRequestContext context) throws Exception {
    Object obj = context.getAttribute(CloudDrive.class);
    if (obj != null) {
      CloudDrive drive = (CloudDrive) obj;
      obj = context.getAttribute(CloudFile.class);
      if (obj != null) {
        initFile(drive, (CloudFile) obj);
        initContext();
      }
    }
    super.processRender(context);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String renderEventURL(boolean ajax, String name, String beanId, Parameter[] params) throws Exception {
    // TODO Auto-generated method stub
    return super.renderEventURL(ajax, name, beanId, params);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void initFile(CloudDrive drive, CloudFile file) {
    this.drive = drive;
    this.file = file;
  }

  public long getFileSize() throws Exception {
    // long size = contentNode.getProperty("jcr:data").getLength()/1024;
    long size = 0; // TODO
    return size;
  }

  public boolean isViewable() {
    return true; // TODO
  }

  public boolean isWebDocument() {
    String mimeType = file.getType();
    return mimeType.startsWith("text/html")
        || mimeType.startsWith("application/rss+xml")
        || mimeType.startsWith("application/xhtml"); // TODO more types
  }
  
  public boolean isXmlDocument() {
    String mimeType = file.getType();
    return mimeType.startsWith("text/xml")
        || mimeType.startsWith("application/xml"); // TODO more types
  }

  /**
   * @return the drive
   */
  public CloudDrive getDrive() {
    return drive;
  }

  /**
   * @return the file
   */
  public CloudFile getFile() {
    return file;
  }

}
