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

package com.dremio.common.util;

import static com.dremio.common.util.DateTimes.isoFormattedLocalDateToJavaTimeMillis;
import static com.dremio.common.util.DateTimes.isoFormattedLocalDateToMillis;
import static com.dremio.common.util.DateTimes.isoFormattedLocalTimestampToJavaTimeMillis;
import static com.dremio.common.util.DateTimes.isoFormattedLocalTimestampToMillis;
import static com.dremio.common.util.DateTimes.javaDateTimeToJoda;
import static com.dremio.common.util.DateTimes.jodaTimeZoneToJavaZoneId;
import static com.dremio.common.util.DateTimes.millisToIsoFormattedLocalDateString;
import static com.dremio.common.util.DateTimes.millisToIsoFormattedLocalTimestampString;
import static com.dremio.common.util.DateTimes.parseIsoLocalDate;
import static com.dremio.common.util.DateTimes.parseIsoLocalTime;
import static com.dremio.common.util.DateTimes.parseIsoLocalTimestamp;
import static com.dremio.common.util.DateTimes.parseIsoLocalTimestampWithSpaces;
import static com.dremio.common.util.DateTimes.parseLocalDateTime;
import static com.dremio.common.util.DateTimes.parseMillis;
import static com.dremio.common.util.DateTimes.parseMillisOffset;
import static com.dremio.common.util.DateTimes.parseMillisZoned;
import static com.dremio.common.util.DateTimes.parseStartOfDay;
import static com.dremio.common.util.DateTimes.toMillis;
import static com.dremio.common.util.DateTimes.toMillisOfDay;

import com.dremio.test.DremioTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import org.joda.time.DateTimeZone;
import org.junit.Assert;
import org.junit.Test;

public class TestDateTimes extends DremioTest {
  @Test
  public void testDateTimeToMillisConversion() {
    // Arrange
    final String formattedDateTime = "2024-08-29T19:10:53+0800";
    final org.joda.time.DateTime dateTime = org.joda.time.DateTime.parse(formattedDateTime);

    // Act
    final long milliseconds = toMillis(dateTime);

    // Assert
    Assert.assertEquals(1724929853000L, milliseconds);
  }

  @Test
  public void testMillisOfDayWithoutTime() {
    // Arrange
    final String formattedDateTime = "2024-08-29";
    final org.joda.time.DateTime dateTime = org.joda.time.DateTime.parse(formattedDateTime);

    // Act
    final long millisOfDay = toMillisOfDay(dateTime);

    // Assert
    Assert.assertEquals(0, millisOfDay);
  }

  @Test
  public void testMillisOfDayWithTimezone() {
    // Arrange
    final String formattedDateTime = "2024-08-29T19:10:53+0800";
    final org.joda.time.DateTime dateTime = org.joda.time.DateTime.parse(formattedDateTime);

    // Act
    final long millisOfDay = toMillisOfDay(dateTime);

    // Assert
    Assert.assertEquals(40253000, millisOfDay);
  }

  @Test
  public void testMillisOfDayWithUtc() {
    // Arrange
    final String formattedDateTime = "2024-08-29T19:10:53+0000";
    final org.joda.time.DateTime dateTime = org.joda.time.DateTime.parse(formattedDateTime);

    // Act
    final long millisOfDay = toMillisOfDay(dateTime);

    // Assert
    Assert.assertEquals(69053000, millisOfDay);
  }

  @Test
  public void testIsoFormattedLocalDateToMillisConversion() {
    // Arrange
    final String isoFormattedLocalDate = "2011-12-03";

    // Act
    final long milliseconds = isoFormattedLocalDateToMillis(isoFormattedLocalDate);

    // Assert
    Assert.assertEquals(1322870400000L, milliseconds);
  }

  @Test
  public void testIsoFormattedLocalTimestampToMillisConversion() {
    // Arrange
    final String isoFormattedLocalTimestamp = "2011-12-03 23:15:46";

    // Act
    final long milliseconds = isoFormattedLocalTimestampToMillis(isoFormattedLocalTimestamp);

    // Assert
    Assert.assertEquals(1322954146000L, milliseconds);
  }

  @Test
  public void testIsoFormattedLocalDateToJavaTimeMillisConversion() {
    // Arrange
    final String jdbcEscapeString = "2011-12-03";

    // Act
    final long milliseconds = isoFormattedLocalDateToJavaTimeMillis(jdbcEscapeString);

    // Assert
    Assert.assertEquals(1322870400000L, milliseconds);
  }

  @Test
  public void testIsoFormattedLocalTimestampToJavaTimeMillisConversion() {
    // Arrange
    final String isoFormattedLocalTimestamp = "2011-12-03 23:15:46";

    // Act
    final long milliseconds =
        isoFormattedLocalTimestampToJavaTimeMillis(isoFormattedLocalTimestamp);

    // Assert
    Assert.assertEquals(1322954146000L, milliseconds);
  }

  @Test
  public void testMillisToIsoFormattedLocalDateFormatting() {
    // Arrange
    final long milliseconds = 1322870400000L;

    // Act
    final String formattedDate = millisToIsoFormattedLocalDateString(milliseconds);

    // Assert
    Assert.assertEquals("2011-12-03", formattedDate);
  }

  @Test
  public void testMillisToIsoFormattedLocalTimestampFormatting() {
    // Arrange
    final long milliseconds = 1322954146000L;

    // Act
    final String formattedDate = millisToIsoFormattedLocalTimestampString(milliseconds);

    // Assert
    Assert.assertEquals("2011-12-03 23:15:46", formattedDate);
  }

  @Test
  public void testJodaTimeZoneToJavaZoneId() {
    // Arrange
    DateTimeZone jodaTimeZone = DateTimeZone.forID("America/New_York");

    // Act
    ZoneId result = jodaTimeZoneToJavaZoneId(jodaTimeZone);

    // Assert
    ZoneId expected = ZoneId.of("America/New_York");
    Assert.assertEquals(expected, result);
  }

  // region toMillis

  @Test
  public void testToMillisJodaDateTime() {
    // Arrange
    org.joda.time.DateTime dateTime =
        new org.joda.time.DateTime(2024, 8, 29, 19, 10, 53, DateTimeZone.UTC);

    // Act
    long result = toMillis(dateTime);

    // Assert
    Assert.assertEquals(1724958653000L, result);
  }

  @Test
  public void testToMillisOfDayJodaDateTime() {
    // Arrange
    org.joda.time.DateTime dateTime =
        new org.joda.time.DateTime(2024, 8, 29, 19, 10, 53, DateTimeZone.UTC);

    // Act
    int result = toMillisOfDay(dateTime);

    // Assert
    int expectedMillisOfDay =
        19 * 3600 * 1000 + 10 * 60 * 1000 + 53 * 1000; // Milliseconds from 00:00 to 19:10:53
    Assert.assertEquals(expectedMillisOfDay, result);
  }

  @Test
  public void testToMillisOfDayJavaLocalTime() {
    // Arrange
    LocalTime localTime = LocalTime.of(19, 10, 53, 123_000_000); // 19:10:53.123

    // Act
    int result = toMillisOfDay(localTime);

    // Assert
    int expectedMillisOfDay =
        19 * 3600 * 1000
            + 10 * 60 * 1000
            + 53 * 1000
            + 123; // Milliseconds from 00:00 to 19:10:53.123
    Assert.assertEquals(expectedMillisOfDay, result);
  }

  // endregion

  // region toMillis(java.time.LocalDateTime)

  @Test
  public void testToMillisJavaLocalDateTime() {
    // Arrange
    LocalDateTime localDateTime = LocalDateTime.of(2024, 8, 29, 19, 10, 53);

    // Act
    long result = toMillis(localDateTime);

    // Assert
    Assert.assertEquals(1724958653000L, result);
  }

  // endregion

  // region toMillis(org.joda.time.LocalDateTime)

  @Test
  public void testToMillisJodaLocalDateTimeValid() {
    // Arrange
    org.joda.time.LocalDateTime jodaLocalDateTime =
        new org.joda.time.LocalDateTime(2024, 8, 29, 19, 10, 53);

    // Act
    long result = toMillis(jodaLocalDateTime);

    // Assert
    Assert.assertEquals(1724958653000L, result);
  }

  // endregion

  // region parseMillis

  @Test
  public void testParseMillisUTC() {
    // Arrange
    final long millis = 1724929853000L;

    // Act
    final LocalDateTime localDateTime = parseMillis(millis);

    // Assert
    final LocalDateTime expectedDateTime = LocalDateTime.of(2024, 8, 29, 11, 10, 53);
    Assert.assertEquals(expectedDateTime, localDateTime);
  }

  @Test
  public void testParseMillisWithTimeZone() {
    // Arrange
    final long millis = 1724929853000L;
    final ZoneId zoneId = ZoneId.of("Asia/Shanghai");

    // Act
    final LocalDateTime localDateTime = parseMillis(millis, zoneId);

    // Assert
    final LocalDateTime expectedDateTime = LocalDateTime.of(2024, 8, 29, 19, 10, 53);
    Assert.assertEquals(expectedDateTime, localDateTime);
  }

  // endregion

  // region parseMillisZoned

  @Test
  public void testParseMillisZonedWithZoneId() {
    // Arrange
    final long millis = 1724929853000L;
    final ZoneId zoneId = ZoneId.of("Asia/Shanghai"); // UTC+8

    // Act
    final ZonedDateTime zonedDateTime = parseMillisZoned(millis, zoneId);

    // Assert
    ZonedDateTime expectedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneId);
    Assert.assertEquals(expectedDateTime, zonedDateTime);
    Assert.assertEquals(2024, zonedDateTime.getYear());
    Assert.assertEquals(8, zonedDateTime.getMonthValue());
    Assert.assertEquals(29, zonedDateTime.getDayOfMonth());
    Assert.assertEquals(19, zonedDateTime.getHour()); // 11:10:53Z + 8 hours = 19:10:53
    Assert.assertEquals(10, zonedDateTime.getMinute());
    Assert.assertEquals(53, zonedDateTime.getSecond());
    Assert.assertEquals(zoneId, zonedDateTime.getZone());
  }

  @Test
  public void testParseMillisZonedWithZoneOffset() {
    // Arrange
    final long millis = 1724929853000L; // Corresponds to 2024-08-29T11:10:53Z
    final ZoneOffset zoneOffset = ZoneOffset.ofHours(-5); // UTC-5

    // Act
    final ZonedDateTime zonedDateTime = parseMillisZoned(millis, zoneOffset);

    // Assert
    ZonedDateTime expectedDateTime =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneOffset);
    Assert.assertEquals(expectedDateTime, zonedDateTime);
    Assert.assertEquals(2024, zonedDateTime.getYear());
    Assert.assertEquals(8, zonedDateTime.getMonthValue());
    Assert.assertEquals(29, zonedDateTime.getDayOfMonth());
    Assert.assertEquals(6, zonedDateTime.getHour()); // 11:10:53Z - 5 hours = 6:10:53
    Assert.assertEquals(10, zonedDateTime.getMinute());
    Assert.assertEquals(53, zonedDateTime.getSecond());
    Assert.assertEquals(zoneOffset, zonedDateTime.getOffset());
  }

  // endregion

  // region parseMillisOffset

  @Test
  public void testParseMillisOffset() {
    // Arrange
    final long millis = 1724929853000L;
    final ZoneId zoneId = ZoneId.of("America/New_York"); // UTC-4 during DST

    // Act
    final OffsetDateTime offsetDateTime = parseMillisOffset(millis, zoneId);

    // Assert
    OffsetDateTime expectedDateTime =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneId);
    Assert.assertEquals(expectedDateTime, offsetDateTime);
    Assert.assertEquals(ZoneOffset.ofHours(-4), offsetDateTime.getOffset());
    Assert.assertEquals(2024, offsetDateTime.getYear());
    Assert.assertEquals(8, offsetDateTime.getMonthValue());
    Assert.assertEquals(29, offsetDateTime.getDayOfMonth());
    Assert.assertEquals(7, offsetDateTime.getHour()); // 11:10:53Z - 4 hours = 7:10:53
    Assert.assertEquals(10, offsetDateTime.getMinute());
    Assert.assertEquals(53, offsetDateTime.getSecond());
  }

  // endregion

  // region parseLocalDateTime

  @Test
  public void testParseLocalDateTimeValid() {
    // Arrange
    String dateTimeStr = "29-08-2024 at 19*10*53";
    DateTimeFormatter customFormatter =
        new DateTimeFormatterBuilder().appendPattern("dd-MM-yyyy 'at' HH*mm*ss").toFormatter();

    // Act
    LocalDateTime result = parseLocalDateTime(dateTimeStr, customFormatter);

    // Assert
    LocalDateTime expected = LocalDateTime.of(2024, 8, 29, 19, 10, 53);
    Assert.assertEquals(expected, result);
  }

  @Test(expected = DateTimeParseException.class)
  public void testParseLocalDateTimeInvalidFormat() {
    // Arrange
    String dateTimeStr = "Invalid Date";
    DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // Act
    //noinspection ResultOfMethodCallIgnored
    parseLocalDateTime(dateTimeStr, formatter);
  }

  // endregion

  // region parseIsoLocalTimestamp

  @Test
  public void testParseIsoLocalTimestampValid() {
    // Arrange
    String dateTimeStr = "2024-08-29T19:10:53";

    // Act
    LocalDateTime result = parseIsoLocalTimestamp(dateTimeStr);

    // Assert
    LocalDateTime expected = LocalDateTime.of(2024, 8, 29, 19, 10, 53);
    Assert.assertEquals(expected, result);
  }

  @Test(expected = DateTimeParseException.class)
  public void testParseIsoLocalTimestampInvalidFormat() {
    // Arrange
    String dateTimeStr = "2024/08/29 19:10:53";

    // Act
    //noinspection ResultOfMethodCallIgnored
    parseIsoLocalTimestamp(dateTimeStr);
  }

  // endregion

  // region parseIsoLocalTimestampWithSpaces

  @Test
  public void testParseIsoLocalTimestampWithSpacesValid() {
    // Arrange
    String dateTimeStr = "2024-08-29 19:10:53";

    // Act
    LocalDateTime result = parseIsoLocalTimestampWithSpaces(dateTimeStr);

    // Assert
    LocalDateTime expected = LocalDateTime.of(2024, 8, 29, 19, 10, 53);
    Assert.assertEquals(expected, result);
  }

  @Test(expected = DateTimeParseException.class)
  public void testParseIsoLocalTimestampWithSpacesInvalidFormat() {
    // Arrange
    String dateTimeStr = "2024-08-29T19:10:53"; // Incorrect separator

    // Act
    //noinspection ResultOfMethodCallIgnored
    parseIsoLocalTimestampWithSpaces(dateTimeStr);
  }

  // endregion

  // region parseIsoLocalTime

  @Test
  public void testParseIsoLocalTimeValid() {
    // Arrange
    String timeStr = "19:10:53";

    // Act
    LocalDateTime result = parseIsoLocalTime(timeStr);

    // Assert
    LocalDateTime expected = LocalDateTime.of(LocalDate.now(), LocalTime.of(19, 10, 53));
    Assert.assertEquals(expected, result);
  }

  @Test(expected = DateTimeParseException.class)
  public void testParseIsoLocalTimeInvalidFormat() {
    // Arrange
    String timeStr = "19-10-53"; // Incorrect format

    // Act
    parseIsoLocalTime(timeStr);
  }

  // endregion

  // region parseIsoLocalDate

  @Test
  public void testParseIsoLocalDateValid() {
    // Arrange
    String dateStr = "2024-08-29";

    // Act
    LocalDateTime result = parseIsoLocalDate(dateStr);

    // Assert
    LocalDateTime expected = LocalDateTime.of(2024, 8, 29, 0, 0);
    Assert.assertEquals(expected, result);
  }

  @Test(expected = DateTimeParseException.class)
  public void testParseIsoLocalDateInvalidFormat() {
    // Arrange
    String dateStr = "08/29/2024"; // Incorrect format

    // Act
    parseIsoLocalDate(dateStr);
  }

  // endregion

  // region parseStartOfDay

  @Test
  public void testParseStartOfDayValid() {
    // Arrange
    String dateStr = "2024-08-29";

    // Act
    LocalDateTime result = parseStartOfDay(dateStr);

    // Assert
    LocalDateTime expected = LocalDateTime.of(2024, 8, 29, 0, 0);
    Assert.assertEquals(expected, result);
  }

  @Test(expected = DateTimeParseException.class)
  public void testParseStartOfDayInvalidFormat() {
    // Arrange
    String dateStr = "August 29, 2024"; // Incorrect format

    // Act
    parseStartOfDay(dateStr);
  }

  // endregion

  @Test
  public void testCompareEqualLocalDateTimes() {
    // Arrange
    final java.time.LocalDateTime javaLocalDateTime =
        LocalDateTime.of(2024, 8, 29, 19, 10, 53, 345 * 1_000_000);
    final org.joda.time.LocalDateTime jodaLocalDateTime =
        new org.joda.time.LocalDateTime(2024, 8, 29, 19, 10, 53, 345);

    // Act
    final boolean result =
        DateTimes.compareDateTimeLikeObjects(jodaLocalDateTime, javaLocalDateTime);

    // Assert
    Assert.assertTrue(result);
  }

  @Test
  public void testCompareDifferentLocalDateTimes() {
    // Arrange
    final java.time.LocalDateTime javaLocalDateTime =
        LocalDateTime.of(2024, 8, 30, 19, 10, 53, 345 * 1_000_000);
    final org.joda.time.LocalDateTime jodaLocalDateTime =
        new org.joda.time.LocalDateTime(2024, 8, 29, 19, 10, 53, 345);

    // Act
    final boolean result =
        DateTimes.compareDateTimeLikeObjects(jodaLocalDateTime, javaLocalDateTime);

    // Assert
    Assert.assertFalse(result);
  }

  @Test
  public void testCompareEqualLocalDates() {
    // Arrange
    final java.time.LocalDate javaLocalDate = LocalDate.of(2024, 8, 29);
    final org.joda.time.LocalDateTime jodaLocalDateTime =
        new org.joda.time.LocalDateTime(2024, 8, 29, 0, 0, 0);

    // Act
    final boolean result = DateTimes.compareDateTimeLikeObjects(jodaLocalDateTime, javaLocalDate);

    // Assert
    Assert.assertTrue(result);
  }

  @Test
  public void testCompareDifferentLocalDates() {
    // Arrange
    final java.time.LocalDate javaLocalDate = LocalDate.of(2024, 8, 29);
    final org.joda.time.LocalDateTime jodaLocalDateTime =
        new org.joda.time.LocalDateTime(2024, 8, 28, 0, 0, 0);

    // Act
    final boolean result = DateTimes.compareDateTimeLikeObjects(jodaLocalDateTime, javaLocalDate);

    // Assert
    Assert.assertFalse(result);
  }

  @Test
  public void testCompareEqualLocalTimes() {
    // Arrange
    final java.time.LocalTime javaLocalTime = LocalTime.of(19, 10, 53, 345 * 1_000_000);
    final org.joda.time.LocalDateTime jodaLocalDateTime =
        new org.joda.time.LocalDateTime(1970, 1, 1, 19, 10, 53, 345);

    // Act
    final boolean result = DateTimes.compareDateTimeLikeObjects(jodaLocalDateTime, javaLocalTime);

    // Assert
    Assert.assertTrue(result);
  }

  @Test
  public void testCompareDifferentLocalTimes() {
    // Arrange
    final java.time.LocalTime javaLocalTime = LocalTime.of(19, 13, 53, 345 * 1_000_000);
    final org.joda.time.LocalDateTime jodaLocalDateTime =
        new org.joda.time.LocalDateTime(1970, 1, 1, 19, 10, 53, 345);

    // Act
    final boolean result = DateTimes.compareDateTimeLikeObjects(jodaLocalDateTime, javaLocalTime);

    // Assert
    Assert.assertFalse(result);
  }

  @Test
  public void testJavaLocalDatetimeToJodaConversion() {
    // Arrange
    final LocalDateTime javaDateTime = LocalDateTime.of(2017, 1, 12, 0, 0, 0);

    // Act
    final org.joda.time.LocalDateTime jodaDateTime = javaDateTimeToJoda(javaDateTime);

    // Assert
    assertSameLocalDateTime(javaDateTime, jodaDateTime);
  }

  private static void assertSameLocalDateTime(
      LocalDateTime javaLocalDateTime, org.joda.time.LocalDateTime jodaLocalDateTime) {

    Assert.assertEquals("Year mismatch", javaLocalDateTime.getYear(), jodaLocalDateTime.getYear());
    Assert.assertEquals(
        "Month mismatch", javaLocalDateTime.getMonthValue(), jodaLocalDateTime.getMonthOfYear());
    Assert.assertEquals(
        "Day mismatch", javaLocalDateTime.getDayOfMonth(), jodaLocalDateTime.getDayOfMonth());
    Assert.assertEquals(
        "Hour mismatch", javaLocalDateTime.getHour(), jodaLocalDateTime.getHourOfDay());
    Assert.assertEquals(
        "Minute mismatch", javaLocalDateTime.getMinute(), jodaLocalDateTime.getMinuteOfHour());
    Assert.assertEquals(
        "Second mismatch", javaLocalDateTime.getSecond(), jodaLocalDateTime.getSecondOfMinute());
    Assert.assertEquals(
        "Millisecond mismatch",
        javaLocalDateTime.getNano() / 1_000_000,
        jodaLocalDateTime.getMillisOfSecond());
  }
}
