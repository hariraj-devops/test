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
import { connect } from "react-redux";
import PropTypes from "prop-types";
import {
  getIconAltTextByEntityType,
  getIconAltTextBySourceType,
  getSourceStatusIcon,
  getIconByEntityType,
} from "utils/iconUtils";
import { ENTITY_TYPES } from "#oss/constants/Constants";
import { getEntity } from "#oss/selectors/resources";
import { getRootEntityTypeByIdV3 } from "#oss/selectors/home";
import { Tooltip } from "dremio-ui-lib/components";
import { isVersionedSource } from "@inject/utils/sourceUtils";
import { withEntityProps } from "dyn-load/utils/entity-utils";

const mapStateToPropsForEntityIcon = (state, { entityId }) => {
  const type = getRootEntityTypeByIdV3(state, entityId);
  const props = {
    entityType: type,
    sourceStatus: null,
    sourceType: null,
  };

  if (type === ENTITY_TYPES.source) {
    // this work only for v2 api. We should think how to change this when we start migrate sources to v3 api
    const entity = getEntity(state, entityId, type);
    props.sourceStatus = entity.getIn(["state", "status"], null);
    props.sourceType = entity.get("type");
  }

  return props;
};

/**
 * Class that renders an entity icon based on entityId for home root entities (sources, spaces and home space).
 * If entityId is empty or entity with this id is not found in redux store, then icon is defaulted to home icon
 */
@connect(mapStateToPropsForEntityIcon)
export class EntityIcon extends PureComponent {
  static propTypes = {
    //public api entityId
    entityId: PropTypes.string,
    entityType: PropTypes.oneOf([
      ENTITY_TYPES.home,
      ENTITY_TYPES.source,
      ENTITY_TYPES.space,
    ]).isRequired,

    //connected
    sourceStatus: PropTypes.string, // available only for sources
    sourceType: PropTypes.string, // available only for sources
    isManageAccessEnabled: PropTypes.bool,
  };

  render() {
    const { entityType, sourceStatus, sourceType, isManageAccessEnabled } =
      this.props;
    return (
      <PureEntityIcon
        entityType={entityType}
        sourceStatus={sourceStatus}
        sourceType={sourceType}
        isManageAccessEnabled={isManageAccessEnabled}
      />
    );
  }
}

export class PureEntityIcon extends PureComponent {
  static propTypes = {
    entityType: PropTypes.string.isRequired,
    sourceStatus: PropTypes.string,
    sourceType: PropTypes.string,
    style: PropTypes.object,
    isManageAccessEnabled: PropTypes.bool,
    enableTooltip: PropTypes.bool,
    tooltipPortal: PropTypes.bool,
  };

  render() {
    const {
      entityType,
      sourceStatus,
      sourceType,
      style,
      isManageAccessEnabled,
      enableTooltip = true,
      tooltipPortal,
    } = this.props;

    const isSource = entityType?.toLowerCase() === "source";
    const iconType = isSource
      ? getSourceStatusIcon(sourceStatus, sourceType)
      : getIconByEntityType(
          entityType,
          isVersionedSource(sourceType),
          isManageAccessEnabled,
        ); //TODO Need to replace all of these to use dremio-icon
    const iconAltText =
      (isSource
        ? getIconAltTextBySourceType(sourceType)
        : getIconAltTextByEntityType(entityType)) || "";

    const Content = (
      <div className="dremio-icon-label mr-05">
        <dremio-icon
          name={iconType}
          style={{
            ...(style?.width && {
              inlineSize: style.width,
            }),
            ...(style?.height && {
              blockSize: style.height,
            }),
          }}
        />
      </div>
    );

    if (!enableTooltip) {
      return Content;
    } else {
      return (
        <Tooltip content={iconAltText} portal={tooltipPortal}>
          {Content}
        </Tooltip>
      );
    }
  }
}

export default withEntityProps(EntityIcon);
