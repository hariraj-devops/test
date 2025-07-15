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

import { useRef } from "react";
import Immutable from "immutable";
import { WithRouterProps } from "react-router";
import HomeContents from "@inject/pages/HomePage/subpages/HomeContents";
import { useIsLoading, useLoadHomeContents } from "./homeContentsUtils";

export type HomeContentsWrapperProps = {
  location: WithRouterProps["location"];
  entityType: string;
  getContentUrl: string;
  sources: Immutable.Map<string, any>;
  sourceName: string;
  source: Immutable.Map<string, unknown> | null;
  params: { refType?: string; refValue?: string } | null;
};

const HomeContentsWrapper = (props: HomeContentsWrapperProps) => {
  const { entityType, source, getContentUrl, params, sources, sourceName } =
    props;
  const prevLoad = useRef<AbortController | null>(null);
  const isLoading = useIsLoading(source, sourceName, sources);
  const load = useLoadHomeContents({
    prevLoad,
    getContentUrl,
    entityType,
    params,
  });
  if (isLoading) return null;

  return <HomeContents {...props} load={load} />;
};

export default HomeContentsWrapper;
