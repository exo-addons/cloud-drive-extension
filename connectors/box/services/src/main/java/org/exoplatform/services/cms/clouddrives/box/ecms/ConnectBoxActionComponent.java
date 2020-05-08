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
package org.exoplatform.services.cms.clouddrives.box.ecms;

import org.exoplatform.ecm.webui.component.explorer.control.listener.UIActionBarActionListener;
import org.exoplatform.services.cms.clouddrives.webui.BaseConnectActionComponent;
import org.exoplatform.webui.config.annotation.ComponentConfig;
import org.exoplatform.webui.config.annotation.EventConfig;
import org.exoplatform.webui.event.Event;

/**
 * The Class ConnectBoxActionComponent.
 */
@ComponentConfig(events = { @EventConfig(listeners = ConnectBoxActionComponent.ConnectBoxActionListener.class) })
public class ConnectBoxActionComponent extends BaseConnectActionComponent {

  /**
   * Box.com id from configuration - box.
   */
  protected static final String PROVIDER_ID = "box";

  /**
   * The listener interface for receiving connectBoxAction events. The class
   * that is interested in processing a connectBoxAction event implements this
   * interface, and the object created with that class is registered with a
   * component using the component's <code>addConnectBoxActionListener</code>
   * method. When the connectBoxAction event occurs, that object's appropriate
   * method is invoked.
   */
  public static class ConnectBoxActionListener extends UIActionBarActionListener<ConnectBoxActionComponent> {

    /**
     * {@inheritDoc}
     */
    public void processEvent(Event<ConnectBoxActionComponent> event) throws Exception {
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
