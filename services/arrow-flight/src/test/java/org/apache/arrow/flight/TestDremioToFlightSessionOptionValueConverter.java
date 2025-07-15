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
package org.apache.arrow.flight;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dremio.sabot.rpc.user.SessionOptionValue;
import com.dremio.service.flight.utils.TestSessionOptionValueVisitor;
import org.junit.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestDremioToFlightSessionOptionValueConverter {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "test-string",
        "",
        " ",
        "a very long string with multiple characters and spaces",
        "emoji-😊",
        "non-ascii-字符",
      })
  public void testConvertStringValue(String input) {
    SessionOptionValue stringOption =
        SessionOptionValue.Builder.newBuilder().setStringValue(input).build();

    org.apache.arrow.flight.SessionOptionValue result =
        DremioToFlightSessionOptionValueConverter.convert(stringOption);
    String actualValue = result.acceptVisitor(new TestSessionOptionValueVisitor<>());
    assertEquals(input, actualValue);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testConvertBooleanValue(boolean input) {
    SessionOptionValue boolOption =
        SessionOptionValue.Builder.newBuilder().setBoolValue(input).build();

    org.apache.arrow.flight.SessionOptionValue result =
        DremioToFlightSessionOptionValueConverter.convert(boolOption);
    Boolean actualValue = result.acceptVisitor(new TestSessionOptionValueVisitor<>());
    assertEquals(input, actualValue);
  }

  @ParameterizedTest
  @ValueSource(longs = {123L, -123L, 0L, Long.MAX_VALUE, Long.MIN_VALUE})
  public void testConvertLongValue(long input) {
    SessionOptionValue longOption =
        SessionOptionValue.Builder.newBuilder().setInt64Value(input).build();

    org.apache.arrow.flight.SessionOptionValue result =
        DremioToFlightSessionOptionValueConverter.convert(longOption);
    Long actualValue = result.acceptVisitor(new TestSessionOptionValueVisitor<>());
    assertEquals(input, actualValue);
  }

  @ParameterizedTest
  @ValueSource(doubles = {123.45, -678.90, 0.0, Double.MAX_VALUE, Double.MIN_VALUE})
  public void testConvertDoubleValue(double input) {
    SessionOptionValue doubleOption =
        SessionOptionValue.Builder.newBuilder().setDoubleValue(input).build();

    org.apache.arrow.flight.SessionOptionValue result =
        DremioToFlightSessionOptionValueConverter.convert(doubleOption);
    Double actualValue = result.acceptVisitor(new TestSessionOptionValueVisitor<>());
    assertEquals(input, actualValue, 0.001);
  }

  @ParameterizedTest
  @ValueSource(strings = {"value1,value2", "", "singlevalue", "value1,value2,value3,value4,value5"})
  public void testConvertStringListValue(String input) {
    String[] inputArray = input.isEmpty() ? new String[0] : input.split(",");
    SessionOptionValue listOption =
        SessionOptionValue.Builder.newBuilder()
            .setStringListValue(java.util.List.of(inputArray))
            .build();

    org.apache.arrow.flight.SessionOptionValue result =
        DremioToFlightSessionOptionValueConverter.convert(listOption);
    String[] actualValue = result.acceptVisitor(new TestSessionOptionValueVisitor<>());
    assertArrayEquals(inputArray, actualValue);
  }

  @Test
  public void testConvertVoidValue() {
    SessionOptionValue voidOption = SessionOptionValue.Builder.newBuilder().build();

    org.apache.arrow.flight.SessionOptionValue result =
        DremioToFlightSessionOptionValueConverter.convert(voidOption);
    assertTrue(result.isEmpty());
  }
}
