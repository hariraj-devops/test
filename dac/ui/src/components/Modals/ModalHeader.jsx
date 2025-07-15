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

import EllipsedText from "components/EllipsedText";
import { h2White } from "uiTheme/radium/typography";
import { modalPadding } from "uiTheme/radium/modal";
import { IconButton } from "dremio-ui-lib/components";

export default class ModalHeader extends PureComponent {
  static propTypes = {
    title: PropTypes.string,
    hideCloseButton: PropTypes.bool,
    hide: PropTypes.func,
    className: PropTypes.string,
    endChildren: PropTypes.node,
    type: PropTypes.string,
    headerIcon: PropTypes.node,
    addShadow: PropTypes.bool,
  };

  static defaultProps = {
    hideCloseButton: false,
    className: "",
  };

  constructor(props) {
    super(props);
  }

  render() {
    const {
      title,
      hide,
      hideCloseButton,
      className,
      endChildren,
      headerIcon,
      addShadow,
      iconDisabled,
    } = this.props;

    const addShadowClass = addShadow ? "add-shadow" : "";

    const iconStyles = iconDisabled
      ? { ...styles.cancel, ...styles.disabledStyle }
      : styles.cancel;
    return (
      <div
        className={`modal-header ${addShadowClass} ${className}`}
        style={styles.base}
      >
        {headerIcon && headerIcon}
        <EllipsedText style={styles.title} text={title} />
        {endChildren && endChildren}
        {!hideCloseButton && (
          <IconButton style={iconStyles} onClick={hide} aria-label="Close">
            <dremio-icon name="interface/close-big"></dremio-icon>
          </IconButton>
        )}
      </div>
    );
  }
}

const styles = {
  base: {
    ...modalPadding,
    height: 56,
    display: "flex",
    justifyContent: "space-between",
    flexShrink: 0,
    alignItems: "center",
  },
  title: {
    ...h2White,
    fontSize: 16,
    fontWeight: 600,
    color: "var(--text--primary)",
  },
  cancel: {
    // todo: this likely should be a button with :hover/:focus/:active styles
    display: "flex",
    justifyContent: "center",
    alignItems: "center",
    cursor: "pointer",
    marginLeft: 8,
  },
  disabledStyle: {
    opacity: "0.5",
    pointerEvents: "none",
  },
  cancelIcon: {
    Icon: {
      color: "#fff",
    },
  },
};
