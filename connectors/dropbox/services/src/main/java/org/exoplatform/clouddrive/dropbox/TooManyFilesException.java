
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
package org.exoplatform.clouddrive.dropbox;

import org.exoplatform.clouddrive.NotAcceptableException;

/**
 * Dropbox doesn't accept creation, modification or removal if a file due to too many files would be involved
 * in the operation for it to complete successfully. The limit is currently 10,000 files and folders (as for
 * July 2015, Dropbox API ver1.7.7).<br>
 * 
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: TooManyFilesException.java 00000 Jul 21, 2015 pnedonosko $
 * 
 */
public class TooManyFilesException extends NotAcceptableException {

  /**
   * Instantiates a new too many files exception.
   *
   * @param message the message
   */
  public TooManyFilesException(String message) {
    super(message);
  }

  /**
   * Instantiates a new too many files exception.
   *
   * @param message the message
   * @param cause the cause
   */
  public TooManyFilesException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Instantiates a new too many files exception.
   *
   * @param cause the cause
   */
  public TooManyFilesException(Throwable cause) {
    super(cause);
  }

}
