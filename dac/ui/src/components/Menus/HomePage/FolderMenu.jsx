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
import Immutable from "immutable";

import { removeSpaceFolder } from "actions/resources/spaceDetails";
import { showConfirmationDialog } from "actions/confirmation";

import FolderMenuMixin from "dyn-load/components/Menus/HomePage/FolderMenuMixin";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";

@FolderMenuMixin
export class FolderMenu extends Component {
  static contextTypes = {
    location: PropTypes.object.isRequired,
    username: PropTypes.string,
  };

  static propTypes = {
    folder: PropTypes.instanceOf(Immutable.Map),
    closeMenu: PropTypes.func.isRequired,
    removeSpaceFolder: PropTypes.func.isRequired,
    showConfirmationDialog: PropTypes.func.isRequired,
  };

  constructor(props) {
    super(props);
    this.removeFolder = this.removeFolder.bind(this);
  }

  removeFolder() {
    const { t } = getIntlContext();
    const { folder, closeMenu } = this.props;
    this.props.showConfirmationDialog({
      title: t("Folder.Delete"),
      text: t("Delete.Confirmation", {
        name: folder.get("name"),
      }),
      confirmText: t("Common.Actions.Delete"),
      confirm: () => this.props.removeSpaceFolder(folder),
      confirmButtonStyle: "primary-danger",
    });
    closeMenu();
  }

  render() {
    return <>{this.renderCompletely()}</>;
  }
  //   renderRenameLink() {
  //     const { location } = this.context;
  //     const { folder, closeMenu } = this.props;
  //     const isVirtual = folder.get('id').startsWith('/space');
  //
  //     if (isVirtual) {
  //       return null;
  //     }
  //     return (
  //       <MenuItemLink
  //         href={{
  //           ...location,
  //           state: {modal: 'RenameModal', entityId: folder.get('id'), entityType: 'folder'}
  //         }}
  //         text={laDeprecated('Rename Folder')}
  //         closeMenu={closeMenu}/>
  //     );
  //   }
}

export default connect(null, {
  removeSpaceFolder,
  showConfirmationDialog,
})(FolderMenu);
