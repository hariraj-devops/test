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
import { Map, fromJS } from "immutable";
import Modal from "#oss/components/Modals/Modal";
import ConfirmCancelFooter from "#oss/components/Modals/ConfirmCancelFooter";
import { showUnsavedChangesConfirmDialog } from "#oss/actions/confirmation";
import MarkdownEditor from "#oss/components/MarkdownEditor";
import ViewStateWrapper from "#oss/components/ViewStateWrapper";
import ApiUtils from "#oss/utils/apiUtils/apiUtils";
import { SectionTitle } from "#oss/pages/ExplorePage/components/Wiki/SectionTitle";
import { modalBody, content, footer, editor } from "./WikiModal.less";

export class WikiModalView extends PureComponent {
  static propTypes = {
    isOpen: PropTypes.bool,
    wikiValue: PropTypes.string,
    wikiViewState: PropTypes.instanceOf(Map),
    topSectionButtons: SectionTitle.propTypes.buttons,
    onChange: PropTypes.func, // () => void;
    isReadMode: PropTypes.bool,
    save: PropTypes.func, // (newWikiValue) => void;
    cancel: PropTypes.func, // () => void;
    entityId: PropTypes.string,
    entityType: PropTypes.string,
    fullPath: PropTypes.any,
    wikiSummary: PropTypes.bool,
  };
  editor = null; // stores editor ref

  componentDidUpdate(prevProps) {
    const { isOpen } = this.props;

    if (this.editor && isOpen && isOpen !== prevProps.isOpen) {
      // modal has been just opened
      this.editor.focus();
    }
  }

  onEditorRef = (editorComp) => {
    this.editor = editorComp;
    if (editorComp) {
      editorComp.focus();
    }
  };

  onSave = () => {
    const { save, wikiValue } = this.props;

    save(this.editor ? this.editor.getValue() : wikiValue);
  };

  render() {
    const {
      isOpen,
      onChange,
      cancel,
      wikiValue,
      wikiViewState,
      isReadMode,
      topSectionButtons,
      entityId,
      entityType,
      fullPath,
      wikiSummary,
    } = this.props;

    const wrapperStylesFix = {
      flex: 1,
      height: "100%",
      display: "flex",
      alignItems: "stretch",
    };

    return (
      <Modal
        size="large"
        title={laDeprecated("Wiki")}
        isOpen={isOpen}
        hide={cancel}
      >
        <div className={modalBody}>
          <div className={content} data-qa="wikiModal">
            {topSectionButtons && <SectionTitle buttons={topSectionButtons} />}
            <ViewStateWrapper
              viewState={wikiViewState}
              hideChildrenWhenFailed={false}
              style={wrapperStylesFix}
            >
              <MarkdownEditor
                ref={this.onEditorRef}
                fullPath={fullPath}
                value={wikiValue}
                readMode={isReadMode}
                onChange={onChange}
                entityId={entityId}
                showSummary={wikiSummary}
                entityType={entityType}
                className={editor}
                isModal
                fitToContainer
              />
            </ViewStateWrapper>
          </div>
          {!isReadMode && (
            <ConfirmCancelFooter
              className={footer}
              cancelText="Cancel"
              cancel={cancel}
              confirm={this.onSave}
            />
          )}
        </div>
      </Modal>
    );
  }
}

const mapDispatchToProps = {
  confirmUnsavedChanges: showUnsavedChangesConfirmDialog,
};

export class WikiModalWithSave extends PureComponent {
  static propTypes = {
    entityId: PropTypes.string,
    entityType: PropTypes.string,
    wikiSummary: PropTypes.bool,
    fullPath: PropTypes.any,
    isOpen: PropTypes.bool,
    wikiValue: PropTypes.string,
    wikiVersion: PropTypes.number,
    isReadMode: PropTypes.bool,
    topSectionButtons: WikiModalView.propTypes.topSectionButtons,
    onChange: PropTypes.func, // () => void;
    save: PropTypes.func, // ({ text, version }) => void;
    cancel: PropTypes.func, // () => void;
    confirmUnsavedChanges: PropTypes.func, // ({ text: string, confirm: func }) => void
  };

  static defaultProps = {
    isReadMode: false,
  };

  state = {
    wikiViewState: new Map(),
  };

  wikiChanged = false;

  saveWiki = (newValue) => {
    const { wikiVersion, entityId, save } = this.props;

    return ApiUtils.fetch(
      `catalog/${entityId}/collaboration/wiki`,
      {
        method: "POST",
        body: JSON.stringify({
          text: newValue,
          version: wikiVersion,
        }),
      },
      3,
    ).then(
      (response) => {
        this.resetError();
        this.wikiChanged = false;
        return response.json().then(save, () => {}); // ignore json parsing error, but if save it not called, wiki will stay in edit mode
      },
      async (response) => {
        this.setState({
          wikiViewState: fromJS({
            isFailed: true,
            error: {
              message: await ApiUtils.getErrorMessage(
                laDeprecated("Wiki is not saved."),
                response,
              ),
              id: "" + Math.random(),
            },
          }),
        });
      },
    );
  };

  cancel = () => {
    const { confirmUnsavedChanges } = this.props;

    const cancelHandler = () => {
      this.resetError();
      this.wikiChanged = false;
      this.props.cancel();
    };

    if (this.wikiChanged) {
      confirmUnsavedChanges({
        confirm: cancelHandler,
      });
    } else {
      cancelHandler();
    }
  };

  resetError() {
    this.setState({
      wikiViewState: fromJS({}),
    });
  }

  onChange = () => {
    const { onChange } = this.props;
    this.wikiChanged = true;

    if (onChange) {
      onChange();
    }
  };

  render() {
    const {
      isOpen,
      wikiValue,
      isReadMode,
      topSectionButtons,
      entityId,
      entityType,
      fullPath,
      wikiSummary,
    } = this.props;

    const props = {
      isOpen,
      wikiValue,
      entityId,
      entityType,
      fullPath,
      wikiSummary,
      isReadMode,
      topSectionButtons,
      onChange: this.onChange,
      save: this.saveWiki,
      cancel: this.cancel,
      wikiViewState: this.state.wikiViewState,
    };

    return <WikiModalView {...props} />;
  }
}

export const WikiModal = connect(null, mapDispatchToProps)(WikiModalWithSave);
