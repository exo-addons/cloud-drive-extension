/*
 * Copyright (C) 2003-2012 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.clouddrive.ecms;

import org.exoplatform.clouddrive.ecms.filters.SyncingCloudFileFilter;
import org.exoplatform.ecm.webui.component.explorer.UIJCRExplorer;
import org.exoplatform.ecm.webui.component.explorer.UIWorkingArea;
import org.exoplatform.ecm.webui.component.explorer.control.UIActionBar;
import org.exoplatform.ecm.webui.component.explorer.control.UIAddressBar;
import org.exoplatform.ecm.webui.component.explorer.control.UIControl;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.web.application.Parameter;
import org.exoplatform.webui.config.annotation.ComponentConfig;
import org.exoplatform.webui.config.annotation.EventConfig;
import org.exoplatform.webui.core.lifecycle.UIContainerLifecycle;
import org.exoplatform.webui.event.Event;
import org.exoplatform.webui.event.EventListener;
import org.exoplatform.webui.ext.filter.UIExtensionFilter;
import org.exoplatform.webui.ext.filter.UIExtensionFilters;

import java.util.Arrays;
import java.util.List;

/**
 * SyncingFile hidden action in working area used by Cloud Drive Javascript to mark synchronzing files in UI.
 * 
 * Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: RefreshCloudDriveManagerComponent.java 00000 Dec 15, 2014 pnedonosko $
 */
@ComponentConfig(
                 lifecycle = UIContainerLifecycle.class,
                 events = { @EventConfig(
                                         listeners = SyncingFileManagerComponent.SyncingFileActionListener.class) })
public class SyncingFileManagerComponent extends BaseCloudDriveManagerComponent {

  protected static final Log                     LOG        = ExoLogger.getLogger(SyncingFileManagerComponent.class);

  public static final String                     EVENT_NAME = "SyncingFile";

  protected static final List<UIExtensionFilter> FILTERS    = Arrays.asList(new UIExtensionFilter[] { new SyncingCloudFileFilter() });

  public static class SyncingFileActionListener extends EventListener<SyncingFileManagerComponent> {
    public void execute(Event<SyncingFileManagerComponent> event) throws Exception {
      // code adopted from UIAddressBar.RefreshSessionActionListener.execute() -- effect of refresh here, 
      // it should be never invoked (menu action invisible)
      UIJCRExplorer explorer = event.getSource().getAncestorOfType(UIJCRExplorer.class);
      explorer.getSession().refresh(false);
      explorer.refreshExplorer();
      UIWorkingArea workingArea = explorer.getChild(UIWorkingArea.class);
      UIActionBar actionBar = workingArea.getChild(UIActionBar.class);
      UIControl control = explorer.getChild(UIControl.class);
      if (control != null) {
        UIAddressBar addressBar = control.getChild(UIAddressBar.class);
        if (addressBar != null) {
          actionBar.setTabOptions(addressBar.getSelectedViewName());
        }
      }
    }
  }

  @UIExtensionFilters
  public List<UIExtensionFilter> getFilters() {
    return FILTERS;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String renderEventURL(boolean ajax, String name, String beanId, Parameter[] params) throws Exception {
    if (EVENT_NAME.equals(name)) {
      initContext();
    }
    return super.renderEventURL(ajax, name, beanId, params);
  }
}
