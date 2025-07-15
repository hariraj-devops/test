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
import { fromJS } from "immutable";
import { injectIntl } from "react-intl";
import MarkdownEditor from "components/MarkdownEditor";
import ViewStateWrapper from "components/ViewStateWrapper";
import {
  SectionTitle,
  getIconButtonConfig,
} from "#oss/pages/ExplorePage/components/Wiki/SectionTitle";
import WikiEmptyState from "#oss/components/WikiEmptyState";
import { WikiModal } from "#oss/pages/ExplorePage/components/Wiki/WikiModal";
import { wikiSaved as wikiSavedAction } from "#oss/actions/home";
import {
  getWikiValue,
  getWikiVersion,
  isWikiLoading,
  getErrorInfo,
} from "#oss/selectors/home";
import {
  wikiWrapper,
  wikiWidget,
  emptyContainer,
  editor,
  wikiTitle,
} from "./WikiView.less";
import { WikiEmptyStateARS } from "#oss/components/WikiEmptyStateARS";
import { isVersionedSource } from "@inject/utils/sourceUtils";

const ViewModes = ["default", "edit", "expanded"].reduce(
  (enumResult, value) => {
    enumResult[value] = value;
    return enumResult;
  },
  {},
);

const getEntityId = (item) => {
  return item && item.get("id");
};

export const getCanEditWiki = (item) => {
  if (!item || !item.get) return false;

  // can edit if entity has no permissions (true for home space and in CE) or explicit canEdit permission is present
  if (["Home", "EnterpriseHome"].includes(item.get("@type"))) return true;

  return !!(
    item.getIn(["permissions", "canEdit"]) ||
    item.getIn(["permissions", "canAlter"])
  );
};

const mapStateToProps = (state, /* ownProps */ { item }) => {
  const entityId = getEntityId(item);
  const error = getErrorInfo(state, entityId);
  return {
    wikiText: getWikiValue(state, entityId) || "",
    wikiVersion: getWikiVersion(state, entityId),
    entityId,
    canEditWiki: getCanEditWiki(item),
    isLoading: isWikiLoading(state, entityId),
    errorMessage: error ? error.message : null,
    errorId: error ? error.id : null,
  };
};

@injectIntl
@connect(mapStateToProps, {
  wikiSaved: wikiSavedAction,
})
export default class WikiView extends Component {
  static propTypes = {
    intl: PropTypes.object.isRequired,
    item: PropTypes.object.isRequired,

    // connected
    entityId: PropTypes.string,
    wikiText: PropTypes.string.isRequired,
    wikiVersion: PropTypes.number,
    canEditWiki: PropTypes.bool.isRequired,
    isLoading: PropTypes.bool,
    errorMessage: PropTypes.string,
    errorId: PropTypes.string,
    wikiSaved: PropTypes.func, // (entityId, text, version) => void
    isArsEnabled: PropTypes.bool,
    isArsLoading: PropTypes.bool,
    setRef: PropTypes.func.isRequired,
  };

  state = {
    wikiSummary: false,
    viewMode: ViewModes.default,
  };

  editWiki = (e) => {
    e.stopPropagation();
    e.preventDefault();
    this.setState({
      viewMode: ViewModes.edit,
      wikiSummary: false,
    });
  };

  addSummary = (e) => {
    e.stopPropagation();
    e.preventDefault();
    this.setState({
      viewMode: ViewModes.edit,
      wikiSummary: true,
    });
  };

  expandWiki = () => {
    this.setState({
      viewMode: ViewModes.expanded,
    });
  };

  onSaveWiki = ({ text, version }) => {
    const { wikiSaved, entityId } = this.props;

    wikiSaved(entityId, text, version);
    this.setState({
      viewMode: ViewModes.default,
    });
  };

  stopEditWiki = () => {
    this.setState({
      viewMode: ViewModes.default,
    });
  };

  componentDidMount() {
    this.props.setRef({
      editWiki: this.editWiki,
      expandWiki: this.expandWiki,
    });
  }

  componentWillUnmount() {
    this.props.setRef(null);
  }

  getTitleButtons() {
    const { canEditWiki, wikiText } = this.props;

    // if there is now wiki we should show only 'Add wiki' button. The other buttons are redundant
    if (!wikiText) return [];

    const buttonList = [
      getIconButtonConfig({
        key: "expand",
        icon: "interface/expand-wiki",
        dataQa: "expand-wiki",
        altText: this.props.intl.formatMessage({ id: "Common.ExpandWiki" }),
        onClick: this.expandWiki,
      }),
    ];

    if (canEditWiki) {
      buttonList.push(this.getEditButton());
    }
    return buttonList;
  }

  getEditButton() {
    return getIconButtonConfig({
      key: "edit",
      icon: "interface/edit",
      dataQa: "edit-wiki",
      altText: this.props.intl.formatMessage({ id: "Common.Edit" }),
      onClick: this.editWiki,
      styles: {
        width: 17,
        height: 18,
        color: "var(--icon--primary)",
      },
    });
  }

  getViewState() {
    //todo apply reselect here
    const { isLoading, errorMessage, errorId, isArsLoading } = this.props;

    if (errorMessage) {
      return fromJS({
        isFailed: true,
        error: {
          message: errorMessage,
          id: errorId,
        },
      });
    }
    return fromJS({ isInProgress: isLoading || isArsLoading });
  }

  render() {
    const { viewMode, wikiSummary } = this.state;

    const {
      wikiText,
      wikiVersion,
      canEditWiki,
      entityId,
      item,
      isArsEnabled,
      isArsLoading,
    } = this.props;
    const entityType =
      item && (item.get("entityType") || item.get("type") || item.get("@type"));
    let Empty = (
      <WikiEmptyState
        className={emptyContainer}
        onAddWiki={canEditWiki ? this.editWiki : null}
        onAddSummary={this.addSummary}
      />
    );

    const showArsWiki =
      isArsEnabled && item && isVersionedSource(item.get("type"));
    if (showArsWiki) {
      Empty = (
        <WikiEmptyStateARS onAddWiki={canEditWiki ? this.editWiki : null} />
      );
    }

    return (
      <ViewStateWrapper
        viewState={this.getViewState()}
        hideChildrenWhenFailed={false}
        style={styles.wrapperStylesFix}
      >
        <div className={wikiWrapper} data-qa="wikiWrapper">
          {!showArsWiki && (
            <SectionTitle
              title={laDeprecated("Wiki")}
              titleClass={wikiTitle}
              buttons={this.getTitleButtons()}
            />
          )}
          {wikiText.length ? (
            <div className={wikiWidget} data-qa="wikiWidget">
              <MarkdownEditor
                fullPath={item?.get("fullPathList")}
                value={wikiText}
                className={editor}
                entityId={entityId}
                entityType={entityType}
                readMode
                fitToContainer
              />
            </div>
          ) : (
            <>{!isArsLoading && Empty}</>
          )}
          <WikiModal
            isOpen={viewMode !== ViewModes.default}
            isReadMode={viewMode === ViewModes.expanded}
            topSectionButtons={
              viewMode === ViewModes.expanded && canEditWiki
                ? [this.getEditButton()]
                : null
            }
            fullPath={item?.get("fullPathList")}
            entityId={entityId}
            entityType={entityType}
            wikiValue={wikiText}
            wikiVersion={wikiVersion}
            save={this.onSaveWiki}
            cancel={this.stopEditWiki}
            wikiSummary={wikiSummary}
          />
        </div>
      </ViewStateWrapper>
    );
  }
}

const styles = {
  icon: {
    height: 16,
    width: 16,
  },
  wrapperStylesFix: {
    height: "100%",
  },
};
