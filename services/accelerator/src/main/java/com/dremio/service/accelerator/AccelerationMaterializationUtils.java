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
package com.dremio.service.accelerator;

import com.dremio.datastore.ProtostuffSerializer;
import com.dremio.datastore.Serializer;
import com.dremio.exec.store.sys.accel.AccelerationListManager;
import com.dremio.service.job.proto.JoinAnalysis;
import com.dremio.service.reflection.ReflectionUtils;
import com.dremio.service.reflection.store.MaterializationStore;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.Iterator;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper methods for extracting a list of {@link
 * com.dremio.service.reflection.proto.Materialization} from a {@link MaterializationStore} and
 * converting them to a list of {@link
 * com.dremio.exec.store.sys.accel.AccelerationListManager.MaterializationInfo}.
 */
public class AccelerationMaterializationUtils {
  private static final Logger logger =
      LoggerFactory.getLogger(AccelerationMaterializationUtils.class);
  private static final Serializer<JoinAnalysis, byte[]> JOIN_ANALYSIS_ABSTRACT_SERIALIZER =
      ProtostuffSerializer.of(JoinAnalysis.getSchema());

  public static Iterator<AccelerationListManager.MaterializationInfo> getMaterializationsFromStore(
      MaterializationStore materializationStore) {
    return StreamSupport.stream(
            ReflectionUtils.getAllMaterializations(materializationStore).spliterator(), false)
        .map(
            materialization -> {
              long footPrint = -1L;
              try {
                footPrint = materializationStore.getMetrics(materialization).left.getFootprint();
              } catch (Exception e) {
                // let's not fail the query if we can't retrieve the footprint for one
                // materialization
              }

              String joinAnalysisJson = null;
              try {
                if (materialization.getJoinAnalysis() != null) {
                  joinAnalysisJson =
                      JOIN_ANALYSIS_ABSTRACT_SERIALIZER.toJson(materialization.getJoinAnalysis());
                }
              } catch (IOException e) {
                logger.debug("Failed to serialize join analysis", e);
              }

              final String failureMsg =
                  materialization.getFailure() != null
                      ? materialization.getFailure().getMessage()
                      : null;

              return new AccelerationListManager.MaterializationInfo(
                  materialization.getReflectionId().getId(),
                  materialization.getId().getId(),
                  new Timestamp(materialization.getCreatedAt()),
                  new Timestamp(Optional.ofNullable(materialization.getExpiration()).orElse(0L)),
                  footPrint,
                  materialization.getSeriesId(),
                  materialization.getInitRefreshJobId(),
                  materialization.getSeriesOrdinal(),
                  joinAnalysisJson,
                  materialization.getState().toString(),
                  Optional.ofNullable(failureMsg).orElse("NONE"),
                  new Timestamp(
                      Optional.ofNullable(materialization.getLastRefreshFromPds()).orElse(0L)),
                  new Timestamp(
                      Optional.ofNullable(materialization.getLastRefreshFinished()).orElse(0L)),
                  Optional.ofNullable(materialization.getLastRefreshDurationMillis()).orElse(0L));
            })
        .iterator();
  }
}
