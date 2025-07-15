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
import { Component, createRef } from "react";
import PropTypes from "prop-types";
import classNames from "clsx";

import MenuItemMaterial from "@mui/material/MenuItem";
import Popper from "@mui/material/Popper";
import Paper from "@mui/material/Paper";
import ClickAwayListener from "@mui/material/ClickAwayListener";

import "./MenuItem.less";

const CLOSE_SUBMENU_DELAY = 100;

class MenuItem extends Component {
  static propTypes = {
    menuItems: PropTypes.array,
    rightIcon: PropTypes.object,
    leftIcon: PropTypes.object,
    title: PropTypes.string,
    onClick: PropTypes.func,
    children: PropTypes.node,
    disabled: PropTypes.bool,
    selected: PropTypes.bool,
    style: PropTypes.object,
    isInformational: PropTypes.bool, // shouldn't look intereactive,
    className: PropTypes.string,
    isSmallDropdown: PropTypes.bool, // this is to have display flex styling so it doesn't break MenuItem styling in other places
    setRef: PropTypes.func,
    tabIndex: PropTypes.number,
    menuItemsPlacement: PropTypes.string,
  };

  constructor(props) {
    super(props);
    this.state = {
      open: false,
      anchorOrigin: {
        horizontal: "right",
        vertical: "top",
      },
      targetOrigin: {
        horizontal: "left",
        vertical: "top",
      },
    };
    this.menuItemRef = createRef();
    this.subMenuRef = createRef();
  }

  delayedCloseTimer = null;

  shouldClose(evt) {
    const enteredElement = evt.relatedTarget;

    if (enteredElement === window) {
      return true; // have seen this case
    }
    return (
      (!this.subMenuRef.current ||
        !this.subMenuRef.current.contains(enteredElement)) &&
      !this.menuItemRef.current.contains(enteredElement)
    );
  }

  handleMouseOver = () => {
    this.handleRequestOpen();
    clearTimeout(this.delayedCloseTimer);
  };

  handleMouseLeave = (evt) => {
    if (this.shouldClose(evt)) {
      this.delayedCloseTimer = setTimeout(
        this.handleRequestClose,
        CLOSE_SUBMENU_DELAY,
      );
    }
  };

  handleRequestOpen = () => {
    this.setState({
      open: true,
    });
  };

  handleRequestClose = () => {
    this.setState({
      open: false,
    });
  };

  render() {
    const {
      menuItems,
      rightIcon,
      leftIcon,
      title,
      onClick,
      disabled,
      selected,
      isInformational,
      style,
      className,
      isSmallDropdown,
      setRef,
      tabIndex = 0,
      menuItemsPlacement,
    } = this.props;
    const itemStyle = {
      ...styles.menuItem,
      ...(isInformational && styles.informational),
      ...(selected && styles.selected),
      ...style,
    };
    const curClassName = classNames({ disabled }, "menu-item-inner", className);
    return (
      <div
        {...(setRef && {
          ref: (cur) => setRef(cur),
        })}
      >
        <MenuItemMaterial
          style={styles.resetStyle}
          onClick={onClick}
          disabled={disabled}
          tabIndex={tabIndex}
          onFocus={this.handleMouseOver}
        >
          <div
            title={title}
            onMouseOver={this.handleMouseOver}
            onMouseLeave={this.handleMouseLeave}
            ref={this.menuItemRef}
            className={curClassName}
            style={itemStyle}
          >
            {isSmallDropdown ? (
              <>
                {leftIcon ? (
                  <div className="menu-item-inner__icon">{leftIcon}</div>
                ) : null}
                <div className="menu-item-inner__innerText">
                  {this.props.children}
                </div>
                {rightIcon ? (
                  <div className="menu-item-inner__icon--right">
                    {rightIcon}
                  </div>
                ) : null}
              </>
            ) : (
              <>
                {leftIcon ? leftIcon : null}
                {this.props.children}
                {rightIcon ? rightIcon : null}
              </>
            )}
          </div>
        </MenuItemMaterial>
        {
          // non-animated because if it is animated then if you quickly toggle #open
          // the popover will stay closed even when it should be open
          // (closing animation "wins" if it is in progress when open set to true?)
          // (plus it feels more responsive to not animate)
          //
          // and need to have extra `&& this.state.open` guard because otherwise can
          // throw an error trying to defocus sub-menu-items while tearing down on close
          //
          // but have to manually apply the theme zIndex because our package is a bit old
          // Can go away with DX-5368
          menuItems && this.state.open && (
            <Popper
              placement={menuItemsPlacement || "right"}
              style={{
                overflow: "visible",
                zIndex: 1300,
              }}
              open={this.state.open}
              anchorEl={this.menuItemRef.current}
              disablePortal
            >
              <ClickAwayListener
                mouseEvent="onMouseDown"
                onClickAway={this.handleRequestClose}
              >
                <Paper
                  ref={this.subMenuRef}
                  onMouseLeave={this.handleMouseLeave}
                  onMouseOver={this.handleMouseOver}
                >
                  {menuItems}
                </Paper>
              </ClickAwayListener>
            </Popper>
          )
        }
      </div>
    );
  }
}

const styles = {
  resetStyle: {
    minHeight: 24,
    padding: 0,
    margin: 0,
  },
  menuItem: {
    fontSize: 14,
    height: 32,
    paddingLeft: 16,
    paddingRight: 16,
    display: "flex",
    alignItems: "center",
    justifyContent: "space-between",
  },
  informational: {
    backgroundColor: "#fff",
    cursor: "default",
  },
  selected: {
    backgroundColor: "var(--fill--primary--selected)",
  },
};
export default MenuItem;
