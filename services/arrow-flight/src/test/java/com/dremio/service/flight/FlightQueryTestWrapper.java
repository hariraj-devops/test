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
package com.dremio.service.flight;

import com.dremio.DremioTestWrapper;
import com.dremio.service.flight.utils.JavaObjectVectorWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Wrapper class for a test which uses a FlightClient to execute a query, check expected column
 * metadata, and validate against baseline results. Only unordered comparisons of results are
 * supported currently.
 */
public class FlightQueryTestWrapper {
  private final FlightClientUtils.FlightClientWrapper flightClientWrapper;
  private final List<Map<String, Object>> baselineRecords;
  private final String query;
  private final Object[] parameters;

  public FlightQueryTestWrapper(
      FlightClientUtils.FlightClientWrapper flightClientWrapper,
      List<Map<String, Object>> baselineRecords,
      String query) {
    this.flightClientWrapper = flightClientWrapper;
    this.baselineRecords = baselineRecords;
    this.query = query;
    this.parameters = new Object[0];
  }

  public FlightQueryTestWrapper(
      FlightClientUtils.FlightClientWrapper flightClientWrapper,
      List<Map<String, Object>> baselineRecords,
      String query,
      Object[] parameters) {
    this.flightClientWrapper = flightClientWrapper;
    this.baselineRecords = baselineRecords;
    this.query = query;
    this.parameters = parameters;
  }

  public void run() throws Exception {
    if (parameters.length == 0) {
      runQuery();
    } else {
      runParameterizedQuery();
    }
  }

  public void runQuery() throws Exception {
    final FlightClient client = flightClientWrapper.getClient();
    final FlightInfo flightInfo =
        (DremioFlightService.FLIGHT_LEGACY_AUTH_MODE.equals(flightClientWrapper.getAuthMode()))
            ? client.getInfo(FlightDescriptor.command(query.getBytes(StandardCharsets.UTF_8)))
            : client.getInfo(
                FlightDescriptor.command(query.getBytes(StandardCharsets.UTF_8)),
                flightClientWrapper.getTokenCallOption());
    final List<Map<String, Object>> flightResults = getResultsFromFlightInfo(flightInfo);
    DremioTestWrapper.compareResults(baselineRecords, flightResults);
  }

  public void runParameterizedQuery() throws Exception {
    final FlightSqlClient client = flightClientWrapper.getSqlClient();
    final FlightSqlClient.PreparedStatement statement =
        client.prepare(query, flightClientWrapper.getOptions());
    try (final VectorSchemaRoot parameterVectors =
        convertToArrowVectors(statement.getParameterSchema(), parameters)) {
      statement.setParameters(parameterVectors);
      final FlightInfo flightInfo = statement.execute(flightClientWrapper.getOptions());
      final List<Map<String, Object>> flightResults = getResultsFromFlightInfo(flightInfo);
      DremioTestWrapper.compareResults(baselineRecords, flightResults);
    }
  }

  private List<Map<String, Object>> getResultsFromFlightInfo(FlightInfo flightInfo)
      throws Exception {
    final FlightClient client = flightClientWrapper.getClient();
    final List<Map<String, Object>> flightResults = new ArrayList<>();

    // Assumption: flightInfo only has one endpoint and the location in the
    // flightInfo is the same as the original endpoint.
    try (FlightStream flightStream =
        (DremioFlightService.FLIGHT_LEGACY_AUTH_MODE.equals(flightClientWrapper.getAuthMode()))
            ? client.getStream(flightInfo.getEndpoints().get(0).getTicket())
            : client.getStream(
                flightInfo.getEndpoints().get(0).getTicket(),
                flightClientWrapper.getTokenCallOption())) {
      while (flightStream.next()) {
        for (int i = 0; i < flightStream.getRoot().getRowCount(); i++) {
          final Map<String, Object> currentRowMap = new LinkedHashMap<>();
          for (FieldVector vector : flightStream.getRoot().getFieldVectors()) {
            currentRowMap.put(vector.getName(), vector.getObject(i));
          }
          flightResults.add(currentRowMap);
        }
      }
    }

    return flightResults;
  }

  private VectorSchemaRoot convertToArrowVectors(Schema parameterSchema, Object[] params) {
    if (parameterSchema.getFields().size() != params.length) {
      throw new IllegalStateException(
          "Prepared statement has "
              + parameterSchema.getFields().size()
              + " parameters specified but "
              + params.length
              + " parameters are provided");
    }

    VectorSchemaRoot parameterVectors =
        VectorSchemaRoot.create(parameterSchema, flightClientWrapper.getAllocator());
    for (int i = 0; i < parameterVectors.getFieldVectors().size(); i++) {
      FieldVector vector = parameterVectors.getVector(i);
      Object value = params[i];
      vector.allocateNew();
      JavaObjectVectorWriter.writeObject(vector, value);
    }

    parameterVectors.setRowCount(1);
    return parameterVectors;
  }
}
