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
package com.dremio.plugins.elastic;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.store.easy.json.reader.BaseJsonProcessor;
import com.dremio.plugins.Version;
import com.dremio.plugins.elastic.execution.CountingElasticsearchJsonReader;
import com.dremio.plugins.elastic.execution.ElasticsearchJsonReader;
import com.dremio.plugins.elastic.execution.FieldReadDefinition;
import com.dremio.sabot.exec.context.OperatorContext;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.gson.JsonObject;
import com.google.gson.internal.LazilyParsedNumber;
import java.util.List;
import org.apache.arrow.vector.complex.reader.FieldReader;

public class ElasticVersionBehaviorProvider {

  private final int elasticMajorVersion;

  public ElasticVersionBehaviorProvider(Version esVersionInCluster) {
    elasticMajorVersion = esVersionInCluster.getMajor();
  }

  public BaseJsonProcessor createCountingElasticSearchReader(
      OperatorContext context,
      List<SchemaPath> columns,
      String resource,
      FieldReadDefinition readDefinition,
      boolean usingElasticProjection,
      boolean metaUIDSelected,
      boolean metaIDSelected,
      boolean metaTypeSelected,
      boolean metaIndexSelected) {
    return new CountingElasticsearchJsonReader(
        context,
        columns,
        resource,
        readDefinition,
        usingElasticProjection,
        metaUIDSelected,
        metaIDSelected,
        metaTypeSelected,
        metaIndexSelected);
  }

  public BaseJsonProcessor createElasticSearchReader(
      OperatorContext context,
      List<SchemaPath> columns,
      String resource,
      FieldReadDefinition readDefinition,
      boolean usingElasticProjection,
      boolean metaUIDSelected,
      boolean metaIDSelected,
      boolean metaTypeSelected,
      boolean metaIndexSelected,
      boolean rowSizeLimitEnabledForReader,
      int fixedDataLenPerRow) {
    return new ElasticsearchJsonReader(
        context,
        columns,
        resource,
        readDefinition,
        usingElasticProjection,
        metaUIDSelected,
        metaIDSelected,
        metaTypeSelected,
        metaIndexSelected,
        rowSizeLimitEnabledForReader,
        fixedDataLenPerRow);
  }

  public String processElasticSearchQuery(String query) {
    // This removes fields which are not needed in ES 7+
    query = query.replaceAll(ElasticsearchConstants.DISABLE_COORD_FIELD, "");
    query = query.replaceAll(ElasticsearchConstants.USE_DIS_MAX, "");
    query = query.replaceAll(ElasticsearchConstants.AUTO_GENERATE_PHRASE_QUERIES, "");
    query = query.replaceAll(ElasticsearchConstants.SPLIT_ON_WHITESPACE, "");
    return query;
  }

  public DateFormats.AbstractFormatterAndType[] getWriteHolderForVersion(List<String> formats) {
    return getFormatterTypeArr(formats, DateFormats.FormatterAndTypeJavaTime::getFormatterAndType);
  }

  public long getMillisGenericFormatter(String dateTime) {
    return DateFormats.FormatterAndTypeJavaTime.getMillisGenericFormatter(dateTime);
  }

  public int geMajorVersion() {
    return elasticMajorVersion;
  }

  public int readTotalResultReader(FieldReader totalResultReader) {
    return readAsInt(
        totalResultReader.reader("hits").reader("total").reader("value").readText().toString());
  }

  public int getSearchResults(JsonObject hits) {
    return hits.get("total").getAsJsonObject().get("value").getAsInt();
  }

  public byte[] getSearchBytes(
      ElasticConnectionPool.ElasticConnection connection, ElasticActions.Search<byte[]> search) {
    return connection.execute(search, elasticMajorVersion);
  }

  public <T> T executeMapping(
      ElasticConnectionPool.ElasticConnection connection,
      ElasticActions.ElasticAction2<T> putMapping) {
    return connection.execute(putMapping, elasticMajorVersion);
  }

  private static DateFormats.AbstractFormatterAndType[] getFormatterTypeArr(
      List<String> formats,
      Function<String, DateFormats.AbstractFormatterAndType> formatterFunction) {
    if (formats != null && !formats.isEmpty()) {
      return FluentIterable.from(formats)
          .transform(formatterFunction)
          .toArray(DateFormats.AbstractFormatterAndType.class);
    }
    return DateFormats.AbstractFormatterAndType.DEFAULT_FORMATTERS;
  }

  private static int readAsInt(String number) {
    return new LazilyParsedNumber(number).intValue();
  }
}
