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
import { useSelector } from "react-redux";
import { browserHistory } from "react-router";
import localStorageUtils from "#oss/utils/storageUtils/localStorageUtils";

export const getHomeObject = () => {
  const userName = localStorageUtils?.getUserData()?.userName || "";
  return {
    name: userName,
    links: {
      self: "/home",
    },
    resourcePath: "/home",
    iconClass: "Home",
  };
};

export const useCanAddSource = () => {
  const canAddSource = useSelector((state: Record<string, any>) => {
    // software permission for creating a source is stored in localstorage,
    // while the permission on cloud is stored in Redux
    return (
      localStorageUtils?.getUserPermissions()?.canCreateSource ||
      state.privileges?.project?.canCreateSource
    );
  });

  return canAddSource;
};

export const useLeftTreeExpandedStates = (): {
  externalSourcesExpanded: boolean;
  objectStorageSourcesExpanded: boolean;
  datasetsExpanded: boolean;
  lakehouseSourcesExpanded: boolean;
} => {
  const expandedStates = useSelector((state: Record<string, any>) => {
    const externalSourcesExpanded = state.ui.get("externalSourcesExpanded");
    const objectStorageSourcesExpanded = state.ui.get(
      "objectStorageSourcesExpanded",
    );
    const datasetsExpanded = state.ui.get("datasetsExpanded");
    const lakehouseSourcesExpanded = state.ui.get("lakehouseSourcesExpanded");
    return {
      externalSourcesExpanded,
      objectStorageSourcesExpanded,
      datasetsExpanded,
      lakehouseSourcesExpanded,
    };
  });
  return expandedStates;
};

export const getAddSourceHref = (
  isExternalSource: boolean,
  isDataPlaneSource: boolean = false,
) => {
  const location = browserHistory.getCurrentLocation();
  return {
    ...location,
    state: {
      modal: "AddSourceModal",
      isExternalSource,
      isDataPlaneSource,
    },
  };
};

export const onScroll = (
  e: React.UIEvent<HTMLDivElement, UIEvent>,
  setAddTopShadow: (arg: boolean) => void,
  setAddBotShadow: (arg: boolean) => void,
) => {
  const scrollTop = e.currentTarget.scrollTop;
  const scrollHeight = e.currentTarget.scrollHeight;
  const offsetHeight = e.currentTarget.offsetHeight;
  if (scrollTop > 0) {
    setAddTopShadow(true);
  } else {
    setAddTopShadow(false);
  }

  if (Math.floor(scrollTop) !== scrollHeight - offsetHeight) {
    setAddBotShadow(true);
  } else {
    setAddBotShadow(false);
  }
};
