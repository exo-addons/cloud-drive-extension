/*
 * Copyright (C) 2003-2013 eXo Platform SAS.
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

import org.exoplatform.clouddrive.CloudProvider;
import org.exoplatform.clouddrive.CloudUser;

/**
 * CMIS user.
 * 
 */
public class CMISUser extends CloudUser {

  protected final CMISAPI api;

  /**
   * An user in-memory POJO.
   * 
   * @param id {@link String}
   * @param username {@link String}
   * @param email {@link String}
   * @param provider {@link CloudProvider}
   * @param api {@link CMISAPI}
   */
  public CMISUser(String id, String username, String email, CloudProvider provider, CMISAPI api) {
    super(id, username, email, provider);
    this.api = api;
  }

  /**
   * Internal API.
   * 
   * @return {@link CMISAPI} instance authenticated for this user.
   */
  CMISAPI api() {
    return api;
  }

  /**
   * Current user's enterprise name. Can be <code>null</code> if user doesn't belong to any enterprise.
   * 
   * @return {@link String} user's enterprise name or <code>null</code>
   */
  public String getEnterpriseName() {
    return "TODO api.getEnterpriseName()";
  }

  /**
   * Current user's enterprise ID. Can be <code>null</code> if user doesn't belong to any enterprise.
   * 
   * @return {@link String} user's enterprise ID or <code>null</code>
   */
  public String getEnterpriseId() {
    return "TODO api.getEnterpriseId()";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CMISProvider getProvider() {
    return (CMISProvider) super.getProvider();
  }
}
