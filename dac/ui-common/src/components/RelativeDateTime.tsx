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
import type { FC } from "react";
import { formatRelativeTimeFromNow } from "../utilities/formatRelativeTimeFromNow";
import { supportedFormats } from "./DateTime";

export const RelativeDateTime: FC<{
  date: Date;
}> = (props) => (
  <time
    dateTime={props.date.toISOString()}
    title={Intl.DateTimeFormat("default", supportedFormats["pretty"]).format(
      props.date,
    )}
  >
    {formatRelativeTimeFromNow(props.date)}
  </time>
);
