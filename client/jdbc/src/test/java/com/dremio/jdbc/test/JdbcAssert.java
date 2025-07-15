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
package com.dremio.jdbc.test;

import com.dremio.jdbc.ConnectionFactory;
import com.dremio.jdbc.ConnectionInfo;
import com.dremio.sabot.rpc.user.UserSession;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.calcite.linq4j.Ord;
import org.junit.Assert;

/** Fluent interface for writing JDBC and query-planning tests. */
public class JdbcAssert {

  private static ConnectionFactory factory = null;

  public static void setFactory(ConnectionFactory factory) {
    JdbcAssert.factory = factory;
  }

  /**
   * Returns default bag of properties that is passed to JDBC connection. By default, includes
   * options to: - turn off the web server - indicate DremioConnectionImpl to set up dfs_test.tmp
   * schema location to an exclusive dir just for this test jvm
   */
  public static Properties getDefaultProperties() {
    final Properties properties = new Properties();
    properties.put(UserSession.USER, "anonymous");

    return properties;
  }

  public static ModelAndSchema withModel(
      final String url, final String model, final String schema) {
    final Properties info = getDefaultProperties();
    info.setProperty("schema", schema);
    info.setProperty("model", "inline:" + model);
    return new ModelAndSchema(url, info, factory);
  }

  public static ModelAndSchema withFull(final String url, final String schema) {
    final Properties info = getDefaultProperties();
    info.setProperty("schema", schema);
    return new ModelAndSchema(url, info, factory);
  }

  public static ModelAndSchema withNoDefaultSchema(final String url) {
    return new ModelAndSchema(url, getDefaultProperties(), factory);
  }

  static String toString(ResultSet resultSet, int expectedRecordCount) throws SQLException {
    final StringBuilder buf = new StringBuilder();
    while (resultSet.next()) {
      final ResultSetMetaData metaData = resultSet.getMetaData();
      final int n = metaData.getColumnCount();
      String sep = "";
      for (int i = 1; i <= n; i++) {
        buf.append(sep)
            .append(metaData.getColumnLabel(i))
            .append("=")
            .append(resultSet.getObject(i));
        sep = "; ";
      }
      buf.append("\n");
    }
    return buf.toString();
  }

  static String toString(ResultSet resultSet) throws SQLException {
    StringBuilder buf = new StringBuilder();
    final List<Ord<String>> columns = columnLabels(resultSet);
    while (resultSet.next()) {
      for (Ord<String> column : columns) {
        buf.append(column.i == 1 ? "" : "; ")
            .append(column.e)
            .append("=")
            .append(resultSet.getObject(column.i));
      }
      buf.append("\n");
    }
    return buf.toString();
  }

  static Set<String> toStringSet(ResultSet resultSet) throws SQLException {
    Builder<String> builder = ImmutableSet.builder();
    final List<Ord<String>> columns = columnLabels(resultSet);
    while (resultSet.next()) {
      StringBuilder buf = new StringBuilder();
      for (Ord<String> column : columns) {
        buf.append(column.i == 1 ? "" : "; ")
            .append(column.e)
            .append("=")
            .append(resultSet.getObject(column.i));
      }
      builder.add(buf.toString());
      buf.setLength(0);
    }
    return builder.build();
  }

  static List<String> toStrings(ResultSet resultSet) throws SQLException {
    final List<String> list = new ArrayList<>();
    StringBuilder buf = new StringBuilder();
    final List<Ord<String>> columns = columnLabels(resultSet);
    while (resultSet.next()) {
      buf.setLength(0);
      for (Ord<String> column : columns) {
        buf.append(column.i == 1 ? "" : "; ")
            .append(column.e)
            .append("=")
            .append(resultSet.getObject(column.i));
      }
      list.add(buf.toString());
    }
    return list;
  }

  private static List<Ord<String>> columnLabels(ResultSet resultSet) throws SQLException {
    int n = resultSet.getMetaData().getColumnCount();
    List<Ord<String>> columns = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      columns.add(Ord.of(i, resultSet.getMetaData().getColumnLabel(i)));
    }
    return columns;
  }

  public static class ModelAndSchema {
    private final Properties info;
    private final ConnectionFactoryAdapter adapter;

    public ModelAndSchema(
        final String url, final Properties info, final ConnectionFactory factory) {
      this.info = info;
      this.adapter =
          new ConnectionFactoryAdapter() {
            @Override
            public Connection createConnection() throws Exception {
              return factory.getConnection(new ConnectionInfo(url, ModelAndSchema.this.info));
            }
          };
    }

    public TestDataConnection sql(String sql) {
      return new TestDataConnection(adapter, sql);
    }

    public <T> T withConnection(Function<Connection, T> function) throws Exception {
      Connection connection = null;
      try {
        connection = adapter.createConnection();
        return function.apply(connection);
      } finally {
        if (connection != null) {
          connection.close();
        }
      }
    }
  }

  public static class TestDataConnection {
    private final ConnectionFactoryAdapter adapter;
    private final String sql;

    TestDataConnection(ConnectionFactoryAdapter adapter, String sql) {
      this.adapter = adapter;
      this.sql = sql;
    }

    /** Checks that the current SQL statement returns the expected result. */
    public TestDataConnection returns(String expected) throws Exception {
      Connection connection = null;
      Statement statement = null;
      try {
        connection = adapter.createConnection();
        statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);
        expected = expected.trim();
        String result = JdbcAssert.toString(resultSet).trim();
        resultSet.close();

        if (!expected.equals(result)) {
          Assert.fail(
              String.format("Generated string:\n%s\ndoes not match:\n%s", result, expected));
        }
        return this;
      } finally {
        if (statement != null) {
          statement.close();
        }
        if (connection != null) {
          connection.close();
        }
      }
    }

    public TestDataConnection returnsSet(Set<String> expected) throws Exception {
      Connection connection = null;
      Statement statement = null;
      try {
        connection = adapter.createConnection();
        statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);
        Set<String> result = JdbcAssert.toStringSet(resultSet);
        resultSet.close();

        if (!expected.equals(result)) {
          Assert.fail(String.format("Generated set:\n%s\ndoes not match:\n%s", result, expected));
        }
        return this;
      } finally {
        if (statement != null) {
          statement.close();
        }
        if (connection != null) {
          connection.close();
        }
      }
    }

    /**
     * Checks that the current SQL statement returns the expected result lines. Lines are compared
     * unordered; the test succeeds if the query returns these lines in any order.
     */
    public TestDataConnection returnsUnordered(String... expecteds) throws Exception {
      Connection connection = null;
      Statement statement = null;
      try {
        connection = adapter.createConnection();
        statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);
        Assert.assertEquals(
            unsortedList(Arrays.asList(expecteds)), unsortedList(JdbcAssert.toStrings(resultSet)));
        resultSet.close();
        return this;
      } finally {
        if (statement != null) {
          statement.close();
        }
        if (connection != null) {
          connection.close();
        }
      }
    }

    public TestDataConnection displayResults(int recordCount) throws Exception {
      // record count check is done in toString method

      Connection connection = null;
      Statement statement = null;
      try {
        connection = adapter.createConnection();
        statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);
        System.out.println(JdbcAssert.toString(resultSet, recordCount));
        resultSet.close();
        return this;
      } finally {
        if (statement != null) {
          statement.close();
        }
        if (connection != null) {
          connection.close();
        }
      }
    }

    private SortedSet<String> unsortedList(List<String> strings) {
      final SortedSet<String> set = new TreeSet<>();
      for (String string : strings) {
        set.add(string + "\n");
      }
      return set;
    }
  }

  private interface ConnectionFactoryAdapter {
    Connection createConnection() throws Exception;
  }
}

// End JdbcAssert.java
