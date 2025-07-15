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
import moize from "moize";
import { Component } from "react";
import PropTypes from "prop-types";
import Modal from "components/Modals/Modal";
import AccelerationController from "components/Acceleration/AccelerationController";
import FormUnsavedWarningHOC from "components/Modals/FormUnsavedWarningHOC";
import "./Modal.less";

export class AccelerationModal extends Component {
  static propTypes = {
    canSubmit: PropTypes.bool,
    isOpen: PropTypes.bool,
    hide: PropTypes.func,
    location: PropTypes.object,
    onDone: PropTypes.func,
    // connected from FormUnsavedWarningHOC
    updateFormDirtyState: PropTypes.func,
  };

  onDone = moize((...args) => {
    this.props.onDone?.();
    this.props.hide(...args);
  });

  render() {
    const { canSubmit, isOpen, hide, location } = this.props;
    const { datasetId, containerSelector, preventSaveChanges } =
      location.state || {};

    return (
      <Modal
        size="large"
        title={laDeprecated("Acceleration")}
        isOpen={isOpen}
        hide={hide}
        containerSelector={containerSelector}
      >
        <AccelerationController
          updateFormDirtyState={
            preventSaveChanges ? () => {} : this.props.updateFormDirtyState
          }
          onCancel={hide}
          onDone={this.onDone}
          datasetId={datasetId}
          canSubmit={canSubmit}
          location={location}
          preventSaveChanges={preventSaveChanges}
        />
      </Modal>
    );
  }
}

export default FormUnsavedWarningHOC(AccelerationModal);
