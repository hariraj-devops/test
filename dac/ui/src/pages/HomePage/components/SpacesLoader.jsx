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
import PropTypes from "prop-types";
import { connect } from "react-redux";
import {
  loadSpaceListData,
  ALL_SPACES_VIEW_ID,
} from "#oss/actions/resources/spaces";
import { getViewState } from "#oss/selectors/resources";
import { KeyChangeTrigger } from "#oss/components/KeyChangeTrigger";

const mapStateToProps = (state) => ({
  isDataInvalidated:
    getViewState(state, ALL_SPACES_VIEW_ID).get("invalidated") || false,
});

const mapDispatchToProps = {
  loadSpaceListData,
};

// Component that loads space list
export class SpacesLoader extends PureComponent {
  static propTypes = {
    isDataInvalidated: PropTypes.bool.isRequired,
    loadSpaceListData: PropTypes.func.isRequired,
  };

  componentDidMount() {
    // should force data load on mount as KeyChangeTrigger will call onViewStateInvalidateChange with
    // falsy value on mount, that would not initiate data load
    this.load();
  }

  onViewStateInvalidateChange = (isInvalidated) => {
    // if view state is invalidated we should force data load, as data could be changed on server
    if (isInvalidated) {
      this.load();
    }
  };

  load() {
    this.props
      .loadSpaceListData()
      .then((result) => {
        return result;
      })
      .catch(() => {});
  }

  render() {
    return (
      <KeyChangeTrigger
        keyValue={this.props.isDataInvalidated}
        onChange={this.onViewStateInvalidateChange}
      />
    );
  }
}

export default connect(mapStateToProps, mapDispatchToProps)(SpacesLoader);
