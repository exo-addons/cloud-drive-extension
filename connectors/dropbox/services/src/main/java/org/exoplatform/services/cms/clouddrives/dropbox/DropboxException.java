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
package org.exoplatform.services.cms.clouddrives.dropbox;

import org.exoplatform.services.cms.clouddrives.CloudProviderException;

/**
 * Indicates a problem on provider side or communication problem.<br>
 */
public class DropboxException extends CloudProviderException {

  /**
   * 
   */
  private static final long serialVersionUID = 5047832186331989372L;

  /**
   * Instantiates a new dropbox exception.
   *
   * @param message the message
   */
  public DropboxException(String message) {
    super(message);
  }

  /**
   * Instantiates a new dropbox exception.
   *
   * @param message the message
   * @param cause the cause
   */
  public DropboxException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Instantiates a new dropbox exception.
   *
   * @param cause the cause
   */
  public DropboxException(Throwable cause) {
    super(cause);
  }
}
