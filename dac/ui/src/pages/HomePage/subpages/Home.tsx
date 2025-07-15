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
import { connect } from "react-redux";
import { WithRouterProps } from "react-router";
import CSS from "csstype";
import { rmProjectBase } from "dremio-ui-common/utilities/projectBase.js";
import {
  getNormalizedEntityPathByUrl,
  getSortedSources,
} from "#oss/selectors/home";
import HomePage from "#oss/pages/HomePage/HomePage";
import { getEntityType, getSourceNameFromUrl } from "#oss/utils/pathUtils";
import { getUserName } from "#oss/selectors/account";
import { getRefQueryParams } from "#oss/utils/nessieUtils";
import { getViewState } from "#oss/selectors/resources";
import ViewStateWrapper from "#oss/components/ViewStateWrapper";
import HomeContentsWrapper from "@inject/pages/HomePage/subpages/HomeContentsWrapper";

type HomeProps = {
  style: CSS.Properties;
  router: WithRouterProps;
  location: WithRouterProps["location"];
  allSourcesViewState: Immutable.Map<string, any>;
  homeContentsProps: {
    sources: Immutable.Map<string, unknown>;
    sourceName: string;
    source: Immutable.Map<string, unknown> | null;
    params: { refType?: string; refValue?: string } | null;
    entityType: string;
    getContentUrl: string;
  };
};

const Home = ({
  style,
  location,
  homeContentsProps,
  allSourcesViewState,
}: HomeProps) => {
  return (
    //@ts-ignore
    <HomePage style={style} location={location}>
      <ViewStateWrapper
        style={{ display: "flex", flex: 1 }}
        viewState={allSourcesViewState}
      >
        <HomeContentsWrapper location={location} {...homeContentsProps} />
      </ViewStateWrapper>
    </HomePage>
  );
};

const mapStateToProps = (state: Record<string, any>, props: HomeProps) => {
  const pathname =
    rmProjectBase(window.location.pathname, {
      projectId: props.router.params?.projectId,
    }) || "/";
  const entityType = getEntityType(pathname);
  const getContentUrl = getNormalizedEntityPathByUrl(
    pathname,
    getUserName(state),
  );
  const sourceName = getSourceNameFromUrl(getContentUrl);
  const sources = getSortedSources(state);
  const source =
    sources && sourceName
      ? sources.find(
          (cur: Immutable.Map<string, unknown>) =>
            cur.get("name") === sourceName,
        )
      : null;

  const params =
    entityType && ["source", "folder"].includes(entityType)
      ? getRefQueryParams(state.nessie, sourceName)
      : null;

  return {
    allSourcesViewState: getViewState(state, "AllSources"),
    homeContentsProps: {
      sources,
      sourceName,
      source,
      params,
      entityType,
      getContentUrl,
    },
  };
};
export default connect(mapStateToProps)(Home);
