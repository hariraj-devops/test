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

import { queryOptions, useMutation } from "@tanstack/react-query";
import sentryUtil from "#oss/utils/sentryUtil";
import { queryClient } from "#oss/queryClient";
import { Ok } from "ts-results-es";

const LOCALSTORAGE_KEY = "up";
const QUERY_KEY = "user-preferences";

export type UserPreferences = {
  lineage: {
    showMetadata: boolean;
    showColumns: boolean;
  };
};

const DEFAULT_VALUES: UserPreferences = {
  lineage: {
    showMetadata: false,
    showColumns: false,
  },
};

const getUserPreferences = () => {
  let prefs = null;
  try {
    const cur = localStorage.getItem(LOCALSTORAGE_KEY);
    if (cur) {
      prefs = JSON.parse(atob(cur));
    } else {
      prefs = DEFAULT_VALUES;
    }
  } catch (e) {
    sentryUtil.logException(e, {
      moreInfo: "Failed to parse user preferences for key: " + LOCALSTORAGE_KEY,
    });
    prefs = DEFAULT_VALUES;
  }
  return Ok(prefs as UserPreferences);
};

export const getUserPreferenceByKey = (key: keyof UserPreferences) =>
  queryOptions({
    queryKey: [QUERY_KEY, key],
    queryFn: async () => {
      const prefs = getUserPreferences();
      return prefs.map((prefsData) => prefsData[key]);
    },
    retry: false,
    staleTime: 0,
  });

export const usePatchUserPreferences = () =>
  useMutation({
    mutationFn: async (value: Partial<UserPreferences>) => {
      const curPrefs = getUserPreferences().unwrap();
      const newPrefs = { ...curPrefs, ...value };
      try {
        localStorage.setItem(LOCALSTORAGE_KEY, btoa(JSON.stringify(newPrefs)));
      } catch (e) {
        sentryUtil.logException(e, {
          moreInfo: "Failed to set user preferences",
          attemptedValue: value,
        });
        throw e;
      }
    },
    onSuccess: (_, value: Partial<UserPreferences>) => {
      Object.keys(value).forEach((key) => {
        queryClient.setQueryData([QUERY_KEY, key], Ok(value[key]));
      });
    },
    onError: () => {
      // Error already logged in mutationFn
    },
  });
