/*
 * Copyright (C) 2014 eXo Platform SAS.
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
package org.exoplatform.clouddrive.sharepoint.ecms;

import org.exoplatform.clouddrive.ecms.BaseConnectActionComponent;
import org.exoplatform.ecm.webui.component.explorer.control.listener.UIActionBarActionListener;
import org.exoplatform.webui.config.annotation.ComponentConfig;
import org.exoplatform.webui.config.annotation.EventConfig;
import org.exoplatform.webui.event.Event;

/**
 * Sharepoint UI component for a menu action dedicated to a single provider. Its UI configuration will refer
 * the action by "ConnectSharepoint" key (see in webapp config), rename it for an actual name (e.g. based on provider ID).
 */
@ComponentConfig(
                 events = { @EventConfig(
                                         listeners = ConnectSharepointActionComponent.ConnectSharepointActionListener.class) })
public class ConnectSharepointActionComponent extends BaseConnectActionComponent {

  /**
   * Sharepoint Provider ID from configuration.
   * */
  protected static final String PROVIDER_ID = "sharepoint";

  public static class ConnectSharepointActionListener
                                                      extends
                                                      UIActionBarActionListener<ConnectSharepointActionComponent> {

    public void processEvent(Event<ConnectSharepointActionComponent> event) throws Exception {
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getProviderId() {
    return PROVIDER_ID;
  }
}
