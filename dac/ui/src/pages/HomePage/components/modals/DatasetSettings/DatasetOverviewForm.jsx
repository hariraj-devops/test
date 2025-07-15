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
// todo: rename this file

import { PureComponent } from "react";
import PropTypes from "prop-types";
import Immutable from "immutable";

import { FormattedMessage } from "react-intl";
import { FormBody, FormTitle } from "components/Forms";
import DatasetItemLabel from "components/Dataset/DatasetItemLabel";

import {
  getIconDataTypeFromEntity,
  getIcebergIconTypeFromEntity,
  getIconByEntityType,
} from "utils/iconUtils";
import { isVersionedSource } from "@inject/utils/sourceUtils";

import DatasetOverviewFormMixin from "dyn-load/pages/HomePage/components/modals/DatasetSettings/DatasetOverviewFormMixin";

@DatasetOverviewFormMixin
export default class DatasetOverviewForm extends PureComponent {
  static propTypes = {
    entity: PropTypes.instanceOf(Immutable.Map),
    source: PropTypes.object,
    location: PropTypes.object,
  };

  render() {
    const { entity, source } = this.props;

    if (!entity) {
      return null;
    }

    const typeIcon = isVersionedSource(source?.type)
      ? getIcebergIconTypeFromEntity(entity)
      : getIconDataTypeFromEntity(entity);

    // this is used when opening a versioned datasest's settings while in the SQL Editor
    const alternateIcon = getIconByEntityType(
      entity.get("datasetType"),
      isVersionedSource(source?.type),
    );

    // todo: if a real form likely want wrapped in ModalForm like siblings?
    return (
      <FormBody>
        <FormTitle>
          <FormattedMessage id="Common.Overview" />
        </FormTitle>
        <div style={{ display: "inline-block" }}>
          <DatasetItemLabel
            name={entity.get("name") || entity.get("displayFullPath")?.last()}
            fullPath={
              entity.get("fullPathList") || entity.get("displayFullPath")
            }
            showFullPath
            typeIcon={typeIcon === "FileEmpty" ? alternateIcon : typeIcon}
            showSummaryOverlay={!isVersionedSource(source?.type)}
            hideOverlayActionButtons
          />
        </div>
      </FormBody>
    );
  }
}
