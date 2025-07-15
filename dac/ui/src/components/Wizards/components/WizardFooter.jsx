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
import { Component } from "react";
import PropTypes from "prop-types";
import SampleDataMessage from "#oss/pages/ExplorePage/components/SampleDataMessage";
import classNames from "clsx";
import { base, warning, buttons } from "./WizardFooter.less";
import { connect } from "react-redux";
import { getApproximate } from "#oss/selectors/explore";

class WizardFooter extends Component {
  static propTypes = {
    children: PropTypes.node,
    style: PropTypes.object,
    isPreview: PropTypes.bool,
  };

  renderPreviewWarning() {
    return <SampleDataMessage />;
  }

  render() {
    const { isPreview } = this.props;
    return (
      <div
        className={classNames(["wizard-footer", base])}
        style={this.props.style}
      >
        <div className={buttons}>{this.props.children}</div>
        {isPreview && (
          <div className={warning}>{this.renderPreviewWarning()}</div>
        )}
      </div>
    );
  }
}

function mapStateToProps(state) {
  const location = state.routing.locationBeforeTransitions || {};
  const previewVersion = location.state?.previewVersion;
  const version = location.query.tipVersion;
  const isPreview =
    previewVersion !== "" ? !!previewVersion : getApproximate(state, version);

  return {
    isPreview,
  };
}

export default connect(mapStateToProps, null)(WizardFooter);
