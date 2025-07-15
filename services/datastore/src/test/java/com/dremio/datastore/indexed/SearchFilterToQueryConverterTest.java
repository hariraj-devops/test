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
package com.dremio.datastore.indexed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dremio.datastore.SearchQueryUtils;
import com.dremio.datastore.SearchTypes;
import org.junit.jupiter.api.Test;

class SearchFilterToQueryConverterTest {
  private static final IndexKey USER_INDEX_KEY =
      IndexKey.newBuilder("usr", "USER", String.class)
          .setSortedValueType(SearchTypes.SearchFieldSorting.FieldType.STRING)
          .setIncludeInSearchAllFields(true)
          .build();
  private static final FilterIndexMapping FILTER_INDEX_MAPPING =
      new FilterIndexMapping(USER_INDEX_KEY);

  @Test
  void testToQuery_NotIn() {
    String filter = "usr=out=(user1,user2)";
    assertEquals(
        SearchQueryUtils.and(
            SearchQueryUtils.not(
                SearchQueryUtils.newTermQuery(USER_INDEX_KEY.getIndexFieldName(), "user1")),
            SearchQueryUtils.not(
                SearchQueryUtils.newTermQuery(USER_INDEX_KEY.getIndexFieldName(), "user2"))),
        SearchFilterToQueryConverter.toQuery(filter, FILTER_INDEX_MAPPING));
  }
}
