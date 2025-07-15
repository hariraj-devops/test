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

package com.dremio.dac.model.userpreferences;

import com.dremio.service.userpreferences.proto.UserPreferenceProto;
import java.util.List;

/** Class PreferenceData */
public final class PreferenceData {
  private final UserPreferenceProto.PreferenceType preferenceType;
  private final List<Entity> entities;

  public PreferenceData(
      final UserPreferenceProto.PreferenceType preferenceType, final List<Entity> entities) {
    this.preferenceType = preferenceType;
    this.entities = entities;
  }

  public UserPreferenceProto.PreferenceType getType() {
    return preferenceType;
  }

  public List<Entity> getEntities() {
    return entities;
  }
}
