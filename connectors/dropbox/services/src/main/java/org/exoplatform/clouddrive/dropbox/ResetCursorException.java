
/*
 * Copyright (C) 2003-2018 eXo Platform SAS.
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

/**
 * Indicates that the cursor has been invalidated. Call list_folder to obtain a
 * new cursor.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: ResetCursorException.java 00000 Apr 10, 2018 pnedonosko $
 */
public class ResetCursorException extends DropboxException {

  /**
   * 
   */
  private static final long serialVersionUID = -82844068177844027L;

  /**
   * Instantiates a new reset cursor exception.
   *
   * @param message the message
   */
  public ResetCursorException(String message) {
    super(message);
  }

  /**
   * Instantiates a new reset cursor exception.
   *
   * @param message the message
   * @param cause the cause
   */
  public ResetCursorException(String message, Throwable cause) {
    super(message, cause);
  }

}
