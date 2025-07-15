/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.service.reflection;

public class ChangeCause {

  /** Representing the origin of a change. */
  private final String changeOrigin;

  // Static instances
  public static final ChangeCause UNDEFINED_CAUSE = new ChangeCause("UNDEFINED");
  public static final ChangeCause REST_CREATE_BY_USER_CAUSE =
      new ChangeCause("REST_CREATE_BY_USER");
  public static final ChangeCause SQL_CREATE_BY_USER_CAUSE = new ChangeCause("SQL_CREATE_BY_USER");
  public static final ChangeCause REST_UPDATE_BY_USER_CAUSE =
      new ChangeCause("REST_UPDATE_BY_USER");
  public static final ChangeCause SQL_UPDATE_BY_USER_CAUSE = new ChangeCause("SQL_UPDATE_BY_USER");
  public static final ChangeCause REST_DROP_BY_USER_CAUSE = new ChangeCause("REST_DROP_BY_USER");
  public static final ChangeCause SQL_DROP_BY_USER_CAUSE = new ChangeCause("SQL_DROP_BY_USER");

  public static final ChangeCause REST_DROP_DUE_TO_DEPRECATION_CAUSE =
      new ChangeCause("REST_DROP_DUE_TO_DEPRECATION");
  public static final ChangeCause SQL_DROP_DUE_TO_DEPRECATION_CAUSE =
      new ChangeCause("SQL_DROP_DUE_TO_DEPRECATION");

  /**
   * Constructor for ChangeCause.
   *
   * @param changeOrigin the origin of the change, must not be null
   */
  public ChangeCause(String changeOrigin) {
    if (changeOrigin == null) {
      throw new IllegalArgumentException("ChangeOrigin must not be null");
    }
    this.changeOrigin = changeOrigin;
  }

  /**
   * Gets the origin of the change.
   *
   * @return the change origin
   */
  public String getChangeOrigin() {
    return changeOrigin;
  }
}
