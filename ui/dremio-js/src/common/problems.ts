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

import type { Problem } from "./Problem.ts";

export const unexpectedError = (detail: string) =>
  ({
    detail,
    title: "An unexpected error occurred while processing this request.",
    type: "https://api.dremio.dev/problems/unexpected-error",
  }) as const satisfies Problem;

export const versionConflictError = {
  title:
    "The version of the provided resource is older than the stored version",
  type: "https://api.dremio.dev/problems/version-conflict",
} as const satisfies Problem;
