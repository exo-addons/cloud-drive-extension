
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
package org.exoplatform.clouddrive.cmis.login;

/**
 * Created by The eXo Platform SAS.
 *
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: AuthenticationException.java 00000 Aug 28, 2014 pnedonosko $
 */
public class AuthenticationException extends Exception {

  /** The Constant serialVersionUID. */
  private static final long serialVersionUID = -3332063474498339156L;

  /**
   * Instantiates a new authentication exception.
   */
  public AuthenticationException() {
  }

  /**
   * Instantiates a new authentication exception.
   *
   * @param message the message
   */
  public AuthenticationException(String message) {
    super(message);
  }

  /**
   * Instantiates a new authentication exception.
   *
   * @param message the message
   * @param cause the cause
   */
  public AuthenticationException(String message, Throwable cause) {
    super(message, cause);
  }

}
