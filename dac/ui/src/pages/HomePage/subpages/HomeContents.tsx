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
import Immutable from "immutable";
import { useRef } from "react";
import { WithRouterProps } from "react-router";
import { useDispatch } from "react-redux";
import { updateRightTreeVisibility } from "#oss/actions/ui/ui";
import MainInfo from "../components/MainInfo";
import {
  useHomeContentsStates,
  useIsSourceDisabled,
  useHandleInvalidatedState,
  useHandleAsyncNotification,
  useInitialLoadhomeContents,
  usePrevious,
} from "./homeContentsUtils";

export type HomeContentsProps = {
  location: WithRouterProps["location"];
  entityType: string;
  getContentUrl: string;
  source: Immutable.Map<string, unknown> | null;
  params: { refType?: string; refValue?: string } | null;
  load: () => void;
};

const HomeContents = (props: HomeContentsProps) => {
  const dispatch = useDispatch();
  const { entityType, source, location, load } = props;
  const { viewState, rightTreeVisible, entity } = useHomeContentsStates();
  const prevLoad = useRef<AbortController | null>(null);
  const sourceDisabled = useIsSourceDisabled(source);
  const prevViewState = usePrevious(viewState);

  useInitialLoadhomeContents({
    prevLoad,
    sourceDisabled,
    location,
    load,
  });

  useHandleInvalidatedState({ viewState, load, source, prevViewState });
  useHandleAsyncNotification({ sourceDisabled, source });

  return (
    <MainInfo
      entityType={entityType}
      entity={entity}
      source={source}
      viewState={viewState}
      //@ts-ignore
      updateRightTreeVisibility={() => dispatch(updateRightTreeVisibility())}
      rightTreeVisible={rightTreeVisible}
    />
  );
};

export default HomeContents;
