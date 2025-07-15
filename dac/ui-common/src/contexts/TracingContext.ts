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
export type TracingContext = {
  appEvent: (eventId: string, eventMetadata?: unknown) => void;
};

let tracingContext: TracingContext;

export const setTracingContext = (ctx: TracingContext) =>
  (tracingContext = ctx);

export const getTracingContext = () => {
  if (!tracingContext) {
    throw new Error("TracingContext is not configured");
  }
  return tracingContext;
};
