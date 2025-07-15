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
import { useEffect, useMemo, useRef } from "react";
import Immutable from "immutable";
import { useSelector, useDispatch } from "react-redux";
import { getViewState } from "#oss/selectors/resources";
import { getHomeContents } from "#oss/selectors/home";

import {
  isSourceDisabled,
  isSourceCreating,
} from "#oss/components/DisabledSourceNavItem";
import { addNotification } from "#oss/actions/notification";
import { loadHomeContentWithAbort } from "#oss/actions/home";
import { intl } from "#oss/utils/intl";
import {
  isVersionedSource,
  isLimitedVersionSource,
} from "@inject/utils/sourceUtils";
import { isDefaultReferenceLoading } from "#oss/selectors/nessie/nessie";
import { WithRouterProps } from "react-router";
export const VIEW_ID = "HomeContents";

type HomeContentsStatesProps = {
  viewState: Immutable.Map<string, any>;
  rightTreeVisible: boolean;
  entity: Immutable.Map<string, any>;
};

export const useHomeContentsStates = () => {
  const { viewState, rightTreeVisible, entity } = useSelector(
    (state: Record<string, any>) => {
      return {
        rightTreeVisible: state.ui.get("rightTreeVisible"),
        viewState: getViewState(state, VIEW_ID),
        entity: getHomeContents(state),
      };
    },
  );

  return {
    viewState,
    rightTreeVisible,
    entity,
  } as HomeContentsStatesProps;
};

export const useIsSourceDisabled = (
  source: Immutable.Map<string, unknown> | null,
) => {
  return useMemo(() => {
    if (!source) return false;
    return isSourceDisabled(
      source.get("sourceChangeState") as string,
      source.get("entityType") as string,
    );
  }, [source]);
};

export const useHandleInvalidatedState = ({
  viewState,
  load,
  source,
  prevViewState,
}: {
  viewState: Immutable.Map<string, any>;
  load: any;
  source: Immutable.Map<string, any> | null;
  prevViewState: Immutable.Map<string, any> | null;
}) => {
  useEffect(() => {
    const prevInvalidated =
      (prevViewState &&
        prevViewState.get("invalidated") &&
        prevViewState.get("isInProgress") !== undefined) ||
      (source && !source.get("sourceChangeState"));

    const invalidated =
      (viewState?.get("invalidated") &&
        viewState.get("isInProgress") !== undefined) ||
      (source && !source.get("sourceChangeState"));

    if (invalidated && invalidated !== prevInvalidated) {
      load();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewState]);
};

export const useHandleAsyncNotification = ({
  sourceDisabled,
  source,
}: {
  sourceDisabled: boolean;
  source: Immutable.Map<string, unknown> | null;
}) => {
  const dispatch = useDispatch();
  useEffect(() => {
    if (sourceDisabled) {
      dispatch(
        addNotification(
          intl.formatMessage({
            id: isSourceCreating(source?.get("sourceChangeState") as string)
              ? "Sources.Source.Tooltip.isCreating"
              : "Sources.Source.Tooltip.isUpdating",
          }),
          "info",
          10,
        ),
      );
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceDisabled, dispatch]);
};

export type LoadHomeContentsProps = {
  prevLoad: React.MutableRefObject<AbortController | null>;
  getContentUrl: string;
  entityType: string;
  params: { refType?: string; refValue?: string } | null;
};
export const useLoadHomeContents = ({
  prevLoad,
  getContentUrl,
  entityType,
  params,
}: LoadHomeContentsProps) => {
  const dispatch = useDispatch();
  return () => {
    prevLoad.current?.abort();
    const [abortController, loadHomeContent] = loadHomeContentWithAbort(
      getContentUrl,
      entityType,
      VIEW_ID,
      params,
    );

    prevLoad.current = abortController as AbortController;
    return dispatch(loadHomeContent as any);
  };
};

export const useInitialLoadhomeContents = ({
  prevLoad,
  sourceDisabled,
  load,
  location,
}: {
  prevLoad: React.MutableRefObject<AbortController | null>;
  sourceDisabled: boolean;
  load: any;
  location: WithRouterProps["location"];
}) => {
  useEffect(() => {
    if (sourceDisabled) return;
    const prevLoadCurrent = prevLoad.current;
    load();
    return () => prevLoadCurrent?.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname, sourceDisabled]);
};

export const useIsLoading = (
  source: Immutable.Map<string, unknown> | null,
  sourceName: string,
  sources: Immutable.Map<string, any>,
) => {
  return useSelector((state: Record<string, any>) => {
    const versioned =
      source &&
      !isLimitedVersionSource(source.get("type") as string) &&
      isVersionedSource(source.get("type") as string);
    if (
      (versioned &&
        isDefaultReferenceLoading(
          state.nessie[source.get("name") as string],
        )) ||
      (sourceName && sources.size === 0)
    ) {
      return true;
    } else {
      return false;
    }
  });
};

export const usePrevious = (props: any) => {
  const ref = useRef<any>(null);
  useEffect(() => {
    ref.current = props;
  }, [props]);
  return ref.current;
};
