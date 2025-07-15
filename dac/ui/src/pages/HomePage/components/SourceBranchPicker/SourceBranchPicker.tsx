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
import {
  createNessieContext,
  NessieContext,
} from "#oss/pages/NessieHomePage/utils/context";
import { NessieRootState } from "#oss/types/nessie";
import {
  fetchDefaultReferenceIfNeeded as fetchDefaultReferenceIfNeededAction,
  SetReferenceAction,
} from "#oss/actions/nessie/nessie";
import { connect } from "react-redux";
import { useEffect, useMemo, useRef } from "react";
import { isDefaultReferenceLoading } from "#oss/selectors/nessie/nessie";
import { getRootEntityLinkUrl } from "#oss/selectors/home";
import BranchPicker from "../BranchPicker/BranchPicker";
import { getEndpointFromSource } from "#oss/utils/nessieUtils";
import {
  ARCTIC,
  isVersionedSoftwareSource,
} from "@inject/constants/sourceTypes";
import * as commonPaths from "dremio-ui-common/paths/common.js";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import { Spinner } from "dremio-ui-lib/components";

type ConnectedProps = {
  nessie: NessieRootState;
  fetchDefaultReferenceIfNeeded: any;
  redirectUrl: string;
};

type SourceBranchPickerProps = {
  source: { id: string; name: string; type: string };
  getAnchorEl?: () => HTMLElement;
  position?: any;
  prefix?: string;
  redirect?: boolean;
  onApply?: (stateKey: string, state: SetReferenceAction["payload"]) => void;
  tabIndex?: number;
};

function SourceBranchPicker({
  source,
  nessie,
  fetchDefaultReferenceIfNeeded,
  getAnchorEl,
  position,
  prefix = "", //Prefix for redux state key
  redirectUrl,
  onApply,
  tabIndex,
}: SourceBranchPickerProps & ConnectedProps) {
  const endpoint = getEndpointFromSource(source);
  const pathProps = {
    sourceName: source.name,
    projectId: getSonarContext().getSelectedProjectId?.(),
  };

  const baseUrl = isVersionedSoftwareSource(source.type)
    ? commonPaths.nessieSource.link(pathProps)
    : source.type === ARCTIC
      ? commonPaths.arcticSource.link(pathProps)
      : undefined;

  const context = useMemo(
    () =>
      createNessieContext(
        { id: source.id, name: source.name, endpoint },
        nessie,
        prefix,
        baseUrl,
      ),
    [source.id, source.name, endpoint, prefix, nessie, baseUrl],
  );

  const stateKey = `${prefix}${source.name}`;
  const apiRef = useRef(context.apiV2);
  useEffect(() => {
    fetchDefaultReferenceIfNeeded(stateKey, apiRef.current);
  }, [fetchDefaultReferenceIfNeeded, stateKey]);

  const isLoading = isDefaultReferenceLoading(nessie[stateKey]);
  if (isLoading) {
    return <Spinner />;
  }
  return (
    <div className="sources-branch-picker">
      <NessieContext.Provider value={context}>
        <BranchPicker
          position={position}
          redirectUrl={redirectUrl}
          getAnchorEl={getAnchorEl}
          onApply={onApply}
          tabIndex={tabIndex}
        />
      </NessieContext.Provider>
    </div>
  );
}
const mapStateToProps = (
  state: any,
  { source, redirect = true }: SourceBranchPickerProps,
) => {
  return {
    nessie: state.nessie,
    redirectUrl:
      redirect && source ? getRootEntityLinkUrl(state, source.id) : "",
  };
};
const mapDispatchToProps = {
  fetchDefaultReferenceIfNeeded: fetchDefaultReferenceIfNeededAction,
};
export default connect(mapStateToProps, mapDispatchToProps)(SourceBranchPicker);
