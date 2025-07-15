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

import { isProblem, type Problem } from "./Problem.ts";
import { unexpectedError } from "./problems.ts";

const extractResponseBody = async (res: Response) => {
  let result;

  if (res.headers.get("content-type")?.includes("application/json")) {
    result = await res.json();
  } else {
    result = await res.text();
  }

  switch (res.status) {
    case 401:
      return tokenInvalidError;
    case 500:
    case 502:
    case 503:
    case 504:
      return isDremioApiError(result)
        ? systemError(result.errorMessage)
        : systemError(result);
    default:
      return isDremioApiError(result)
        ? unexpectedError(result.errorMessage)
        : unexpectedError(result);
  }
};

/**
 * @hidden
 * @internal
 */
export class HttpError extends Error {
  readonly status: number;
  readonly body: Problem;
  constructor(status: number, body: Problem) {
    if (typeof body === "string") {
      super(body);
    } else if (isProblem(body)) {
      super(body.title);
    } else {
      super("An unexpected error occurred while processing this request.");
    }

    this.status = status;
    this.body = body;
  }

  static async fromResponse(res: Response) {
    return new HttpError(res.status, await extractResponseBody(res));
  }
}

const systemError = (detail: string) =>
  ({
    detail,
    title:
      "An unexpected error occurred while processing your request. Please contact Dremio support if this problem persists.",
    type: "https://api.dremio.dev/problems/system-error",
  }) as const satisfies Problem;

export const tokenInvalidError = {
  title:
    "The provided authentication token was rejected because it was invalid or may have expired.",
  type: "https://api.dremio.dev/problems/auth/token-invalid",
} as const satisfies Problem;

type DremioApiError = {
  errorMessage: string;
  moreInfo?: string;
};

export const isDremioApiError = (body: unknown): body is DremioApiError =>
  typeof body === "object" &&
  Object.prototype.hasOwnProperty.call(body, "errorMessage");
