//
// Copyright (C) 2017-2019 Dremio Corporation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

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

const getFormatter =
  (options: Intl.DateTimeFormatOptions) =>
  (date: Date): string =>
    new window.Intl.DateTimeFormat("default", options).format(date);

export const fixedDateShort: Intl.DateTimeFormatOptions = {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
};

export const fixedDateLong: Intl.DateTimeFormatOptions = {
  year: "numeric",
  month: "short",
  day: "numeric",
};

export const fixedDateTimeShort: Intl.DateTimeFormatOptions = {
  ...fixedDateShort,
  hour: "numeric",
  minute: "numeric",
};

export const fixedDateTimeLong: Intl.DateTimeFormatOptions = {
  ...fixedDateLong,
  hour: "numeric",
  minute: "numeric",
};

const dateTimestamp: Intl.DateTimeFormatOptions & {
  fractionalSecondDigits: number;
} = {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  timeZoneName: "short",
  fractionalSecondDigits: 2,
  hour12: false,
};

/**
 * Used to show timestamps for technical purposes
 * Example: 01/01/2022, 08:00:00.00 PDT
 */
export const formatDateTimestamp = getFormatter(dateTimestamp);

/**
 * Used to show dates in the app where space is restricted
 * Example: 01/01/2022
 */
export const formatFixedDateShort = getFormatter(fixedDateShort);

/**
 * Used to show dates in the app where there's additional space available
 * Example: Jan 1, 2022
 */
export const formatFixedDateLong = getFormatter(fixedDateLong);

/**
 * Used to show dates + times in the app where space is restricted
 * Example: 01/01/2022, 8:00 AM
 */
export const formatFixedDateTimeShort = getFormatter(fixedDateTimeShort);

/**
 * Used to show dates + times in the app where there's additional space available
 * Example: Jan 1, 2022, 8:00 AM
 */
export const formatFixedDateTimeLong = getFormatter(fixedDateTimeLong);

/**
 * Used to show just the hours portion of a timestamp
 * Example: 2 PM
 */
export const formatHours = getFormatter({
  hour: "numeric",
});

/**
 * Used to show just the hours:minutes portion of a timestamp in a 12h cycle
 * Example: 2:00 PM
 */
export const formatHours12 = getFormatter({
  hour: "numeric",
  minute: "2-digit",
});

/**
 * Used to show just the day of the month portion of a timestamp
 * Example: 24
 */
export const formatDays = getFormatter({
  month: "short",
  day: "numeric",
});
