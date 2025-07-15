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

import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.store.sys.accel.AccelerationListManager;
import com.dremio.service.acceleration.ReflectionDescriptionServiceRPC;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.MaterializationMetrics;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionId;
import java.util.Iterator;
import java.util.Optional;
import org.apache.calcite.util.Pair;

/** Computes the reflection status for a reflections and external reflections */
public interface ReflectionStatusService {

  ReflectionStatus getReflectionStatus(ReflectionId reflectionId);

  ReflectionStatus getReflectionStatus(
      ReflectionGoal goal, Optional<Materialization> lastDoneMaterialization, DremioTable table);

  ExternalReflectionStatus getExternalReflectionStatus(ReflectionId reflectionId);

  Iterator<AccelerationListManager.ReflectionInfo> getReflections();

  Iterator<ReflectionDescriptionServiceRPC.GetRefreshInfoResponse> getRefreshInfos();

  /**
   * Returns the size of all materializations in this reflection including materializations that are
   * deprecated
   *
   * @param reflectionId
   * @return
   */
  long getTotalReflectionSize(ReflectionId reflectionId);

  /**
   * Returns the current size of a reflection including size in bytes and total output records
   *
   * @param materialization
   * @return
   */
  Pair<MaterializationMetrics, Long> getReflectionSize(Materialization materialization);

  ReflectionStatusService NOOP =
      new ReflectionStatusService() {

        @Override
        public ReflectionStatus getReflectionStatus(ReflectionId reflectionId) {
          throw new UnsupportedOperationException("getReflectionStatus");
        }

        @Override
        public ReflectionStatus getReflectionStatus(
            ReflectionGoal goal,
            Optional<Materialization> lastDoneMaterialization,
            DremioTable table) {
          throw new UnsupportedOperationException("getReflectionStatus");
        }

        @Override
        public ExternalReflectionStatus getExternalReflectionStatus(ReflectionId reflectionId) {
          throw new UnsupportedOperationException("getExternalReflectionStatus");
        }

        @Override
        public Iterator<AccelerationListManager.ReflectionInfo> getReflections() {
          throw new UnsupportedOperationException("getReflections");
        }

        @Override
        public Iterator<ReflectionDescriptionServiceRPC.GetRefreshInfoResponse> getRefreshInfos() {
          throw new UnsupportedOperationException("getRefreshInfos");
        }

        @Override
        public long getTotalReflectionSize(ReflectionId reflectionId) {
          return 0;
        }

        @Override
        public Pair<MaterializationMetrics, Long> getReflectionSize(
            Materialization materialization) {
          throw new UnsupportedOperationException("getReflectionSize");
        }
      };
}
