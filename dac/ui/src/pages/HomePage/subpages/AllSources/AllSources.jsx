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
import { PureComponent } from "react";
import { compose } from "redux";
import { connect } from "react-redux";
import Immutable from "immutable";
import shallowEqual from "shallowequal";
import PropTypes from "prop-types";
import DocumentTitle from "react-document-title";
import { injectIntl } from "react-intl";

import HomePage from "pages/HomePage/HomePage";
import { loadSourceListData } from "actions/resources/sources";
import { getHomeSource, getSources } from "#oss/selectors/home";

import {
  isDatabaseType,
  isObjectStorageSourceType,
  isLakehouseSourceType,
} from "@inject/constants/sourceTypes";
import { withCatalogARSFlag } from "@inject/utils/arsUtils";
import AllSourcesView from "./AllSourcesView";

@injectIntl
export class AllSources extends PureComponent {
  static propTypes = {
    location: PropTypes.object.isRequired,
    sources: PropTypes.instanceOf(Immutable.List),
    loadSourceListData: PropTypes.func,
    intl: PropTypes.object.isRequired,

    isArsEnabled: PropTypes.bool,
  };

  UNSAFE_componentWillReceiveProps(nextProps) {
    if (!shallowEqual(this.props.location.query, nextProps.location.query)) {
      this.props.loadSourceListData();
    }
  }

  render() {
    const { location, sources, intl, isArsEnabled } = this.props;
    const loc = location.pathname;
    const homeSource = getHomeSource(sources);
    const isExternalSource = loc.includes("/sources/external/list");
    const isObjectStorageSource = loc.includes("/sources/objectStorage/list");
    const isLakehouseSource = loc.includes("/sources/lakehouse/list");

    /*eslint no-nested-ternary: "off"*/
    const headerId = isExternalSource
      ? "Source.AllDatabaseSources"
      : isObjectStorageSource
        ? "Source.AllObjectStorage"
        : "Source.AllLakehouse";

    const title = intl.formatMessage({ id: headerId });

    const objectStorageSource = sources.filter((source) =>
      isObjectStorageSourceType(source.get("type")),
    );

    const databaseSources = sources.filter((source) =>
      isDatabaseType(source.get("type")),
    );

    const lakehouseSources = sources.filter((source) => {
      if (isArsEnabled) {
        return (
          isLakehouseSourceType(source.get("type")) && source !== homeSource
        );
      } else {
        return isLakehouseSourceType(source.get("type"));
      }
    });

    /*eslint no-nested-ternary: "off"*/
    const filteredSources = isExternalSource
      ? databaseSources
      : isObjectStorageSource
        ? objectStorageSource
        : lakehouseSources;

    return (
      <HomePage location={location}>
        <DocumentTitle title={title} />
        <AllSourcesView
          title={title}
          filters={this.filters}
          isLakehouseSource={isLakehouseSource}
          isExternalSource={isExternalSource}
          isObjectStorageSource={isObjectStorageSource}
          sources={filteredSources}
        />
      </HomePage>
    );
  }
}

function mapStateToProps(state) {
  return {
    sources: getSources(state),
  };
}

export default compose(
  connect(mapStateToProps, {
    loadSourceListData,
  }),
  withCatalogARSFlag,
)(AllSources);
