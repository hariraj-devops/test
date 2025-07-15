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

package com.dremio.sabot.exec;

import static com.dremio.telemetry.api.metrics.CommonTags.TAGS_OUTCOME_SUCCESS;

import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.proto.UserBitShared.QueryId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Abstract strategy for reducing heap usage. */
public abstract class AbstractHeapClawBackStrategy implements HeapClawBackStrategy {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(AbstractHeapClawBackStrategy.class);

  protected FragmentExecutors fragmentExecutors;
  protected QueriesClerk queriesClerk;

  private static final Meter.MeterProvider<Counter> clawBackCounter =
      Counter.builder("query_execution_clawback_total")
          .description("Tracks how many queries were failed as a part of heap clawbacks")
          .withRegistry(Metrics.globalRegistry);

  public AbstractHeapClawBackStrategy(
      FragmentExecutors fragmentExecutors, QueriesClerk queriesClerk) {
    this.queriesClerk = queriesClerk;
    this.fragmentExecutors = fragmentExecutors;
  }

  public static class ActiveQuery {
    QueryId queryId;
    long directMemoryUsed;

    public ActiveQuery(QueryId queryId, long directMemoryUsed) {
      this.queryId = queryId;
      this.directMemoryUsed = directMemoryUsed;
    }
  }

  /**
   * Get the list of active queries, sorted by the used memory.
   *
   * @return list of active queries.
   */
  protected List<ActiveQuery> getSortedActiveQueries() {
    List<ActiveQuery> queryList = new ArrayList<>();

    for (final WorkloadTicket workloadTicket : queriesClerk.getWorkloadTickets()) {
      for (final QueryTicket queryTicket : workloadTicket.getActiveQueryTickets()) {
        queryList.add(
            new ActiveQuery(
                queryTicket.getQueryId(), queryTicket.getAllocator().getAllocatedMemory()));
      }
    }

    // sort in descending order of memory usage.
    queryList.sort(Comparator.comparingLong(x -> -x.directMemoryUsed));
    return queryList;
  }

  /**
   * Fail the queries in the input list.
   *
   * @param queries list of queries to be canceled.
   */
  protected void failQueries(List<QueryId> queries, HeapClawBackContext clawBackContext) {
    final String normalizedTriggerName = clawBackContext.getTrigger().name().toLowerCase();

    clawBackCounter
        .withTags(TAGS_OUTCOME_SUCCESS.and("trigger", normalizedTriggerName))
        .increment(queries.size());

    for (QueryId queryId : queries) {
      logger.error(
          "{} initiated canceling of query {} ",
          clawBackContext.getTrigger().name(),
          QueryIdHelper.getQueryId(queryId));
      fragmentExecutors.failFragments(
          queryId,
          queriesClerk,
          clawBackContext.getCause(),
          clawBackContext.getFailContext(),
          clawBackContext.getTrigger());
    }
  }
}
