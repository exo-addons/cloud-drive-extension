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
package org.exoplatform.clouddrive.PROVIDER_ID.ecms;

import org.exoplatform.clouddrive.ecms.BaseConnectActionComponent;
import org.exoplatform.ecm.webui.component.explorer.control.listener.UIActionBarActionListener;
import org.exoplatform.webui.config.annotation.ComponentConfig;
import org.exoplatform.webui.config.annotation.EventConfig;
import org.exoplatform.webui.event.Event;

/**
 * Template UI component for a menu action dedicated to a single provider. Its UI configuration will refer
 * the action by "ConnectTemplate" key (see in webapp config), rename it for an actual name (e.g. based on provider ID).
 */
@ComponentConfig(
                 events = { @EventConfig(
                                         listeners = ConnectTemplateActionComponent.ConnectPROVIDER_IDActionListener.class) })
public class ConnectTemplateActionComponent extends BaseConnectActionComponent {

  /**
   * TemplateProvider ID from configuration.
   * */
  protected static final String PROVIDER_ID = "YOUR PROVIDER_ID";

  public static class ConnectPROVIDER_IDActionListener
                                                      extends
                                                      UIActionBarActionListener<ConnectTemplateActionComponent> {

    public void processEvent(Event<ConnectTemplateActionComponent> event) throws Exception {
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
