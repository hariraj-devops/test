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
import { connect } from "react-redux";
import { Iterable } from "immutable";
import Modal from "components/Modals/Modal";
import { isURISupportedSource } from "@inject/utils/sourceUtils";
import ApiUtils from "utils/apiUtils/apiUtils";
import { addNewFolderForSpace } from "actions/resources/spaceDetails";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";
import { getSupportFlags } from "#oss/selectors/supportFlags";
import { STORAGE_URI_FOLDER } from "@inject/endpoints/SupportFlags/supportFlagConstants";

import AddUriFolderForm from "../forms/UriFolder/AddUriFolderForm";
import AddFolderForm from "../forms/AddFolderForm";

import "./Modal.less";

export class AddFolderModal extends Component {
  static propTypes = {
    isOpen: PropTypes.bool,
    hide: PropTypes.func,
    rootPath: PropTypes.string,
    rootName: PropTypes.string,

    //connected
    addNewFolderForSpace: PropTypes.func,
    disabled: PropTypes.bool,
  };

  submit = (values) => {
    return ApiUtils.attachFormSubmitHandlers(
      this.props.addNewFolderForSpace(
        values.name,
        this.props.rootPath,
        this.props.rootName,
      ),
    ).then(() => this.props.hide());
  };

  render() {
    const { isOpen, hide, disabled, entity, sources, isStorageUriEnabled } =
      this.props;
    const { t } = getIntlContext();
    const isIterable = Iterable.isIterable(entity);
    const finalEntity = isIterable ? entity.toJS() : entity;
    const rootName = finalEntity?.fullPathList[0];
    const source =
      sources && sources.find((item) => item.get("name") === rootName);
    const isUriSupported = source && isURISupportedSource(source.get("type"));

    return (
      <Modal
        size="small"
        title={t("Folder.Create.Modal.Title")}
        isOpen={isOpen}
        hide={hide}
        style={
          isUriSupported && isStorageUriEnabled
            ? { height: "fit-content", top: "10%" }
            : { height: 250, top: "30%" }
        }
        className="--newModalStyles"
      >
        {isUriSupported && isStorageUriEnabled ? (
          <AddUriFolderForm entity={finalEntity} hide={hide} source={source} />
        ) : (
          <AddFolderForm
            onFormSubmit={this.submit}
            onCancel={hide}
            disabled={disabled}
          />
        )}
      </Modal>
    );
  }
}

export default connect(
  (state) => {
    return {
      disabled: !state.form.addFolder?.name?.value,
      sources: state.resources.entities.get("source"),
      isStorageUriEnabled: getSupportFlags(state)[STORAGE_URI_FOLDER],
    };
  },
  { addNewFolderForSpace },
)(AddFolderModal);
