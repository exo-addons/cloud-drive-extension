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
package org.exoplatform.services.cms.clouddrives.cmis;

import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;

/**
 * Product specific CMIS connector basic functionality. Goal of this interface
 * is to mark product/vendor dedicated implementations of Cloud Drive CMIS
 * connector to support use of that connector instead of default implementation
 * in {@link CMISConnector}.<br>
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: CMISConnectorImpl.java 00000 Oct 26, 2014 pnedonosko $
 */
public interface CMISConnectorImpl {

  /**
   * Does this implementation supports the given repository.
   * 
   * @param repository {@link RepositoryInfo} CMIS repository info
   * @return <code>true</code> if given repository is supported by this
   *         implementation.
   */
  boolean hasSupport(RepositoryInfo repository);

  /**
   * An instance of product specific CMIS connector extending
   * {@link CMISConnector}.
   * 
   * @return {@link CMISConnector} instance
   */
  CMISConnector getConnector();
}
