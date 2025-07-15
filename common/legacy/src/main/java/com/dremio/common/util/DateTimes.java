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

import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.NANO_OF_SECOND;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDateTime;
import org.joda.time.LocalDateTimes;

public final class DateTimes {
  public static final long MILLIS_PER_SECOND = 1000L;
  public static final long MILLIS_PER_DAY = 24 * 60 * 60 * MILLIS_PER_SECOND;
  public static final long NANOS_PER_MILLISECOND = 1_000_000L;
  public static final long MICROS_PER_MILLISECOND = 1_000L;

  /*
   * Formatters used to convert from/to Dremio representation into Calcite representation
   * during constant reduction
   */
  public static final DateTimeFormatter CALCITE_LOCAL_DATE_FORMATTER =
      DateTimeFormatter.ISO_LOCAL_DATE;
  public static final DateTimeFormatter CALCITE_LOCAL_TIME_FORMATTER =
      new DateTimeFormatterBuilder()
          .appendValue(HOUR_OF_DAY, 2)
          .appendLiteral(':')
          .appendValue(MINUTE_OF_HOUR, 2)
          .appendLiteral(':')
          .appendValue(SECOND_OF_MINUTE, 2)
          .optionalStart()
          .appendFraction(NANO_OF_SECOND, 0, 9, true)
          .toFormatter();
  public static final DateTimeFormatter CALCITE_LOCAL_DATETIME_FORMATTER =
      new DateTimeFormatterBuilder()
          .parseCaseInsensitive()
          .append(CALCITE_LOCAL_DATE_FORMATTER)
          .appendLiteral(' ')
          .append(CALCITE_LOCAL_TIME_FORMATTER)
          .toFormatter();
  public static final DateTimeFormatter ISO_LOCAL_DATE_TIME_WITH_SPACE_FORMATTER =
      CALCITE_LOCAL_DATETIME_FORMATTER;

  public static final DateTimeFormatter TIMESTAMP_TO_CHAR_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy MMM dd HH:mm:ss");

  private DateTimes() {}

  /**
   * Converts a {@link java.time.LocalDateTime} to milliseconds since the epoch
   *
   * @param localDateTime the {@code LocalDateTime} to convert
   * @return the number of milliseconds since the epoch for the given {@code LocalDateTime}, based
   *     on UTC
   */
  public static long toMillis(java.time.LocalDateTime localDateTime) {
    return localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  public static long toMillis(java.time.LocalDate localDate) {
    return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
  }

  public static long toMillis(java.time.LocalTime localTime) {
    return localTime.atDate(LocalDate.EPOCH).toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  /**
   * Converts a Joda-Time {@link org.joda.time.LocalDateTime} to milliseconds since the epoch
   *
   * @param localDateTime the Joda-Time {@code LocalDateTime} to convert
   * @return the number of milliseconds since the epoch for the given Joda-Time {@code
   *     LocalDateTime}
   */
  public static long toMillis(org.joda.time.LocalDateTime localDateTime) {
    return LocalDateTimes.getLocalMillis(localDateTime);
  }

  /**
   * Converts a Joda-Time {@link org.joda.time.DateTime} to milliseconds since the epoch
   *
   * @param dateTime the Joda-Time {@code DateTime} to convert
   * @return the number of milliseconds since the epoch for the given {@code DateTime}
   */
  public static long toMillis(DateTime dateTime) {
    return dateTime.toDateTime(DateTimeZone.UTC).getMillis();
  }

  /**
   * Returns the number of milliseconds that have passed since the start of the day in UTC for the
   * given Joda-Time {@link org.joda.time.DateTime}
   *
   * @param dateTime the Joda-Time {@code DateTime} to convert
   * @return the number of milliseconds since the start of the day in UTC
   */
  public static int toMillisOfDay(final DateTime dateTime) {
    return dateTime.toDateTime(DateTimeZone.UTC).millisOfDay().get();
  }

  /**
   * Returns the number of milliseconds that have passed since the start of the day for the given
   * {@link java.time.LocalTime}
   *
   * @param localTime the {@code LocalTime} to convert
   * @return the number of milliseconds since the start of the day for the given time
   */
  public static int toMillisOfDay(final LocalTime localTime) {
    return localTime.toSecondOfDay() * 1000 + localTime.getNano() / 1_000_000;
  }

  /**
   * Converts a Joda-Time {@link org.joda.time.DateTimeZone} to a {@link java.time.ZoneId}
   *
   * @param jodaTimeZone the Joda-Time {@code DateTimeZone} to be converted
   * @return an instance of {@code java.time.ZoneId} representing the same time zone as the provided
   *     Joda-Time {@code DateTimeZone}
   */
  public static java.time.ZoneId jodaTimeZoneToJavaZoneId(
      final org.joda.time.DateTimeZone jodaTimeZone) {
    return java.time.ZoneId.of(jodaTimeZone.getID());
  }

  /**
   * Convert an ISO8601-formatted local date string to millis
   *
   * <p>Note, the current implementation is ridiculous as it goes through two conversions. Should be
   * updated to no conversion.
   *
   * @param isoFormattedLocalDate YYYY-MM-DD
   * @return Milliseconds since epoch
   */
  public static long isoFormattedLocalDateToMillis(String isoFormattedLocalDate) {
    return java.time.LocalDate.parse(isoFormattedLocalDate, DateTimeFormatter.ISO_LOCAL_DATE)
        .atStartOfDay()
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli();
  }

  /**
   * Convert an ISO8601-formatted (with a space "␣" instead of the "T") local timestamp string to
   * millis
   *
   * <p>Note, the current implementation is ridiculous as it goes through two conversions. Should be
   * updated to no conversion.
   *
   * @param isoFormattedLocalTimestamp YYYY-MM-DD hh:mm:ss (only "␣" and not "T" supported to
   *     separate the date from the time)
   * @return Milliseconds since epoch
   */
  public static long isoFormattedLocalTimestampToMillis(String isoFormattedLocalTimestamp) {
    return parseIsoLocalTimestampWithSpaces(isoFormattedLocalTimestamp)
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli();
  }

  /**
   * Convert an ISO8601-formatted local date string to millis
   *
   * @param isoFormattedLocalDate YYYY-MM-DD
   * @return Milliseconds since epoch
   */
  public static long isoFormattedLocalDateToJavaTimeMillis(String isoFormattedLocalDate) {
    java.time.LocalDate localDate =
        java.time.LocalDate.parse(isoFormattedLocalDate, DateTimes.CALCITE_LOCAL_DATE_FORMATTER);
    return localDate.atTime(LocalTime.MIDNIGHT).toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  /**
   * Convert an ISO8601-formatted (with a space "␣" instead of the "T") local timestamp string to
   * millis
   *
   * @param isoFormattedLocalTimestamp YYYY-MM-DD hh:mm:ss (only "␣" and not "T" supported to
   *     separate the date from the time)
   * @return Milliseconds since epoch
   */
  public static long isoFormattedLocalTimestampToJavaTimeMillis(String isoFormattedLocalTimestamp) {
    java.time.LocalDateTime localDateTime =
        parseIsoLocalTimestampWithSpaces(isoFormattedLocalTimestamp);
    return localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  /**
   * Convert millis to ISO8601-formatted local date string
   *
   * @param millis Milliseconds since epoch
   * @return YYYY-MM-DD
   */
  public static String millisToIsoFormattedLocalDateString(long millis) {
    java.time.LocalDateTime localDateTime = parseMillis(millis);
    // LocalDate toString returns ISO format
    return localDateTime.toLocalDate().format(DateTimes.CALCITE_LOCAL_DATE_FORMATTER);
  }

  /**
   * Convert millis to ISO8601-formatted local timestamp string using "␣" instead of the "T" to
   * separate the date and the time
   *
   * @param millis Milliseconds since epoch
   * @return YYYY-MM-DD hh:mm:ss
   */
  public static String millisToIsoFormattedLocalTimestampString(long millis) {
    java.time.LocalDateTime localDateTime = parseMillis(millis);
    return localDateTime.format(DateTimes.CALCITE_LOCAL_DATETIME_FORMATTER);
  }

  public static DateTime toDateTime(org.joda.time.LocalDateTime localDateTime) {
    return localDateTime.toDateTime(DateTimeZone.UTC);
  }

  /**
   * Converts the given {@link java.time.LocalDateTime} to an equivalent {@link
   * org.joda.time.LocalDateTime}. Assumes UTC offset
   *
   * @param localDateTime to convert
   * @return an equivalent to {@code localDateTime}
   */
  public static org.joda.time.LocalDateTime javaDateTimeToJoda(
      java.time.LocalDateTime localDateTime) {
    return new LocalDateTime(
        localDateTime.getYear(),
        localDateTime.getMonthValue(),
        localDateTime.getDayOfMonth(),
        localDateTime.getHour(),
        localDateTime.getMinute(),
        localDateTime.getSecond(),
        (int) (localDateTime.getNano() / NANOS_PER_MILLISECOND));
  }

  /**
   * Converts a timestamp in milliseconds since the epoch to an instance of {@link
   * java.time.ZonedDateTime} using the specified time zone.
   *
   * @param millis the number of milliseconds since the epoch
   * @param zoneId the time zone to use for the conversion
   * @return an instance of {@code ZonedDateTime} representing the timestamp in the specified time
   *     zone
   */
  public static java.time.ZonedDateTime parseMillisZoned(final long millis, final ZoneId zoneId) {
    return java.time.ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneId);
  }

  /**
   * Converts a timestamp in milliseconds since the epoch to an instance of {@link
   * java.time.ZonedDateTime} using the specified zone offset.
   *
   * @param millis the number of milliseconds since the epoch
   * @param zoneOffset the zone offset to use for the conversion
   * @return an instance of {@code ZonedDateTime} representing the timestamp with the specified zone
   *     offset
   */
  public static java.time.ZonedDateTime parseMillisZoned(
      final long millis, final ZoneOffset zoneOffset) {
    return java.time.ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneOffset);
  }

  /**
   * Converts a timestamp in milliseconds since the epoch to an instance of {@link
   * java.time.OffsetDateTime} using the specified time zone.
   *
   * @param millis the number of milliseconds since the epoch
   * @param zoneId the time zone to use for the conversion
   * @return an instance of {@code OffsetDateTime} representing the timestamp in the specified time
   *     zone
   */
  public static java.time.OffsetDateTime parseMillisOffset(final long millis, final ZoneId zoneId) {
    return java.time.OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneId);
  }

  /**
   * Converts a timestamp in milliseconds since the epoch to an instance of {@link
   * java.time.LocalDateTime} using the specified time zone.
   *
   * @param millis the number of milliseconds since the epoch
   * @param zoneId the time zone to use for the conversion
   * @return an instance of {@code LocalDateTime} representing the timestamp in the specified time
   *     zone
   */
  public static java.time.LocalDateTime parseMillis(final long millis, final ZoneId zoneId) {
    return java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneId);
  }

  /**
   * Converts a timestamp in milliseconds since the epoch to a {@link java.time.LocalDateTime} in
   * UTC.
   *
   * @param millis the number of milliseconds since the epoch
   * @return an instance of {@code LocalDateTime} representing the timestamp in UTC
   */
  public static java.time.LocalDateTime parseMillis(final long millis) {
    return parseMillis(millis, ZoneOffset.UTC);
  }

  /**
   * Parses a date-time string using the specified {@link DateTimeFormatter}
   *
   * @param str the date-time string to parse
   * @param formatter the formatter to use for parsing
   * @return an instance of {@code LocalDateTime} representing the parsed date-time
   */
  public static java.time.LocalDateTime parseLocalDateTime(
      final String str, final DateTimeFormatter formatter) {
    return java.time.LocalDateTime.parse(str, formatter);
  }

  /**
   * Parses a date-time string in ISO local date-time format
   *
   * <p>The input string should be in the format {@code yyyy-MM-dd'T'HH:mm:ss} or with optional
   * nanoseconds, as defined by {@link DateTimeFormatter#ISO_LOCAL_DATE_TIME}.
   *
   * @param str the ISO local date-time string to parse
   * @return an instance of {@code LocalDateTime} representing the parsed date-time
   */
  public static java.time.LocalDateTime parseIsoLocalTimestamp(final String str) {
    return parseLocalDateTime(str, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  }

  /**
   * Parses a date-time string in ISO local date-time format with a space ('␣') instead of 'T'
   *
   * <p>The input string should be in the format {@code yyyy-MM-dd HH:mm:ss} or with optional
   * nanoseconds, as defined by {@link DateTimes#ISO_LOCAL_DATE_TIME_WITH_SPACE_FORMATTER}.
   *
   * @param str the ISO local date-time string with spaces to parse
   * @return an instance of {@code LocalDateTime} representing the parsed date-time
   */
  public static java.time.LocalDateTime parseIsoLocalTimestampWithSpaces(final String str) {
    return parseLocalDateTime(str, ISO_LOCAL_DATE_TIME_WITH_SPACE_FORMATTER);
  }

  /**
   * Parses a time string in ISO local time format
   *
   * <p>The input string should be in the format {@code HH:mm:ss} or with optional nanoseconds, as
   * defined by {@link DateTimeFormatter#ISO_LOCAL_TIME}. The date component defaults to the current
   * date.
   *
   * @param str the ISO local time string to parse
   * @return an instance of {@code LocalDateTime} representing the parsed time on the current date
   */
  public static java.time.LocalDateTime parseIsoLocalTime(final String str) {
    return LocalTime.parse(str, DateTimeFormatter.ISO_LOCAL_TIME).atDate(LocalDate.now());
  }

  /**
   * Parses a date string in ISO local date format
   *
   * <p>The input string should be in the format {@code yyyy-MM-dd}, as defined by {@link
   * DateTimeFormatter#ISO_LOCAL_DATE}.
   *
   * @param str the ISO local date string to parse
   * @return an instance of {@code LocalDateTime} representing the start of the parsed date
   */
  public static java.time.LocalDateTime parseIsoLocalDate(final String str) {
    return LocalDate.parse(str, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
  }

  /**
   * Parses a date string in ISO local date format
   *
   * <p>This method is equivalent to calling {@link #parseIsoLocalDate(String)} but is provided for
   * semantic clarity when the intention is explicitly to get the start of the day.
   *
   * @param str the ISO local date string to parse
   * @return an instance of {@code LocalDateTime} representing the start of the parsed date
   */
  public static java.time.LocalDateTime parseStartOfDay(final String str) {
    return parseIsoLocalDate(str);
  }

  public static java.time.LocalDate parseLocalDateFromMillis(final long millis) {
    return java.time.LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
  }

  public static java.time.LocalTime parseLocalTimeFromMillis(final long millis) {
    return java.time.LocalTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
  }

  public static java.time.LocalDateTime parseLocalDateTimeFromMillis(final long millis) {
    return DateTimes.parseLocalDateTimeFromMillis(millis, ZoneOffset.UTC);
  }

  public static java.time.LocalDateTime parseLocalDateTimeFromMillis(
      final long millis, final ZoneId zoneId) {
    return java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneId);
  }

  public static boolean compareDateTimeLikeObjects(
      org.joda.time.LocalDateTime expected, Object actual) {
    if (actual instanceof java.time.LocalDateTime) {
      return compareLocalDateTime(expected, (java.time.LocalDateTime) actual);
    }

    if (actual instanceof java.time.LocalDate) {
      return compareLocalDate(expected, (java.time.LocalDate) actual);
    }

    if (actual instanceof java.time.LocalTime) {
      return compareLocalTime(expected, (java.time.LocalTime) actual);
    }

    if (actual instanceof org.joda.time.LocalDateTime) {
      return expected.equals(actual);
    }

    return false;
  }

  private static boolean compareLocalDateTime(
      org.joda.time.LocalDateTime expected, java.time.LocalDateTime actual) {
    return expected.equals(DateTimes.javaDateTimeToJoda(actual));
  }

  private static boolean compareLocalDate(
      org.joda.time.LocalDateTime expected, java.time.LocalDate actual) {
    return expected.equals(
        DateTimes.javaDateTimeToJoda(java.time.LocalDateTime.of(actual, LocalTime.MIDNIGHT)));
  }

  private static boolean compareLocalTime(
      org.joda.time.LocalDateTime expected, java.time.LocalTime actual) {
    return expected.equals(
        DateTimes.javaDateTimeToJoda(java.time.LocalDateTime.of(LocalDate.EPOCH, actual)));
  }
}
