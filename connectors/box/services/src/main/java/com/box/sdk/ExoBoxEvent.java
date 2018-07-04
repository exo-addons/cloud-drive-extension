
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
package com.box.sdk;

import com.eclipsesource.json.JsonObject;

/**
 * Extensions to Box Content SDK v1.
 * Original {@link BoxEvent} extended to get an access to its constructor based
 * on {@link JsonObject}.<br>
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: ExoBoxEvent.java 00000 Aug 21, 2015 pnedonosko $
 */
public class ExoBoxEvent extends BoxEvent {

  /**
   * Instantiates a new exo box event.
   *
   * @param api the api
   * @param json the json
   */
  public ExoBoxEvent(BoxAPIConnection api, String json) {
    super(api, json);
  }

  /**
   * Instantiates a new exo box event.
   *
   * @param api the api
   * @param jsonObject the json object
   */
  public ExoBoxEvent(BoxAPIConnection api, JsonObject jsonObject) {
    super(api, jsonObject);
  }

}
