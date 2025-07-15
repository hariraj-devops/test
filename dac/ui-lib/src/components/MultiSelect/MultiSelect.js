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

import React, { useState, useRef, useMemo } from "react";
import PropTypes from "prop-types";
import clsx from "clsx";
import { get } from "lodash";

import { Checkbox } from "../Checkbox/index";
import Chip from "@mui/material/Chip";
import Menu from "@mui/material/Menu";
import MenuItem from "@mui/material/MenuItem";
import Tooltip from "../Tooltip/index";

import { ReactComponent as XIcon } from "../../art/XLarge.svg";

import Label from "../Label";

import "./multiSelect.scss";

const MultiSelectComponent = (props) => {
  const {
    classes,
    form: { errors, touched, setFieldValue } = {},
    handleChange,
    label,
    disabled,
    limitTags,
    name,
    options,
    placeholder,
    typeAhead,
    displayValues,
    value,
    onChange,
    loadNextRecords,
    nonClearableValue,
    getCustomChipIcon,
    referToId = false,
  } = props;

  const [showMenu, setShowMenu] = useState(false);
  const [filterText, setFilterText] = useState("");
  const inputRef = useRef(null);
  const valueContainerRef = useRef(null);

  const filteredValues = useMemo(() => {
    const noFilterText = !filterText || filterText === "";
    return (options || []).filter(
      ({ value: optionValue }) =>
        noFilterText ||
        optionValue.toLowerCase().indexOf(filterText.toLowerCase()) !== -1,
    );
  }, [filterText, options]);

  const visibleValues = useMemo(() => {
    const preferredVisibleValues = displayValues.length ? displayValues : value;
    return limitTags && !showMenu
      ? preferredVisibleValues.slice(0, limitTags)
      : preferredVisibleValues;
  }, [value, limitTags, showMenu]); // eslint-disable-line react-hooks/exhaustive-deps

  const hasError = get(touched, name) && get(errors, name);
  const rootClass = clsx("multiSelect", { [classes.root]: classes.root });
  const valueClass = clsx(
    "multiSelect__value",
    { "--error": hasError },
    { [classes.value]: classes.value },
    { "--disabled": disabled },
  );

  const inputClass = clsx("multiSelect__input", "margin-top", {
    [classes.input]: classes.input,
  });

  const inputContainerClass = clsx("multiSelect__inputContainer", {
    "--disabled": disabled,
  });

  const labelClass = clsx("multiSelect__label", {
    [classes.label]: classes.label,
  });

  const updateValue = (updatedValue) => {
    if (setFieldValue && typeof setFieldValue === "function") {
      setFieldValue(name, updatedValue, true);
    }
    if (handleChange && typeof handleChange === "function") {
      handleChange(updatedValue);
    }
  };

  const removeValue = (deleteValue) => {
    const updatedValue = value.filter((selectedVal) =>
      referToId
        ? selectedVal.id !== deleteValue.id
        : selectedVal !== deleteValue,
    );
    updateValue(updatedValue);
  };

  const addValue = (addedValue) => {
    const updatedValue = [...value, addedValue];
    updateValue(updatedValue);
  };

  const handleDelete = (event, deleteValue) => {
    let delValue = deleteValue;
    if (!referToId && displayValues.length) {
      delValue = deleteValue.value;
    }
    removeValue(delValue);
    event.stopPropagation();
  };

  const handleOpen = () => {
    setShowMenu(true);
    inputRef.current.focus();
  };

  const handleClose = () => {
    setShowMenu(false);
  };

  const handleMenuItemClick = (selectedValue) => {
    const existsInDisplayValues = () =>
      !!displayValues.find((option) => option.id === selectedValue.id);
    if (referToId) {
      const exists = existsInDisplayValues();
      if (!exists) {
        addValue(selectedValue);
      } else {
        removeValue(selectedValue);
      }
    } else {
      const exists = value.indexOf(selectedValue.value) !== -1;
      if (!exists) {
        addValue(selectedValue.value);
      } else {
        removeValue(selectedValue.value);
      }
    }
    handleTypeAhead({ currentTarget: { value: "" } });
  };

  const handleChipClick = (e) => {
    e.stopPropagation();
  };

  const handleTypeAhead = (e) => {
    const stringValue = e.currentTarget.value;
    setFilterText(stringValue);
    onChange && onChange(stringValue);
  };

  const handleInputKeyDown = (e) => {
    if (e.key === "Escape" && showMenu) {
      handleClose();
      e.stopPropagation();
      return;
    }

    const noFilterText = !filterText || filterText === "";
    if (noFilterText && value && value.length > 0 && e.key === "Backspace") {
      removeValue(value[value.length - 1]);
    }

    if (e.key === "Enter") {
      e.preventDefault(); // Usually this exists in a form, so this will prevent the form submission
      if (
        !noFilterText &&
        filteredValues.length === 1 &&
        value.findIndex(
          (selectedVal) =>
            selectedVal.toLowerCase() === filteredValues[0].value.toLowerCase(),
        ) === -1
      ) {
        addValue(filteredValues[0].value);
      }
    }

    if (!showMenu && e.key !== "Tab" && e.key !== "Shift") {
      setShowMenu(true);
    }
  };

  const handleScroll = (event) => {
    const {
      target: { scrollHeight, scrollTop, clientHeight },
    } = event;
    const hasReachedBottom = scrollHeight - scrollTop === clientHeight;
    if (hasReachedBottom) {
      loadNextRecords && loadNextRecords(filterText);
    }
  };

  const handleClear = (e) => {
    if (nonClearableValue) {
      updateValue([nonClearableValue]);
    } else {
      updateValue([]);
    }
    onChange && onChange("");
    e.stopPropagation();
  };

  const getDisplayName = (val) => {
    if (displayValues.length) {
      return val.value;
    }
    const { label: displayName = val } =
      options.find(({ value: optionValue }) => val === optionValue) || {};
    return displayName;
  };

  const getChipIcon = (val) => {
    if (displayValues.length) {
      const Icon = val.icon;
      return Icon ? <Icon /> : null;
    }
    const { icon: IconComponent } =
      options.find(({ value: optionValue }) => val === optionValue) || {};
    return IconComponent ? <IconComponent /> : null;
  };

  const renderValue = () => {
    const hasValue = value && value.length > 0;
    const { innerRef } = props;
    return (
      <div
        ref={(node) => {
          valueContainerRef.current = node;
          if (innerRef) {
            innerRef.current = node;
          }
        }}
        className={valueClass}
        onClick={handleOpen}
      >
        <div className={inputContainerClass}>
          {visibleValues.map((selectedVal) => {
            const KEY = displayValues.length > 0 ? selectedVal.id : selectedVal;
            return (
              <Chip
                icon={getChipIcon(selectedVal)}
                classes={{
                  root: clsx(
                    "multiSelect__chip",
                    selectedVal === nonClearableValue &&
                      classes.nonClearableChip,
                  ),
                  icon: "icon --md multiSelect__chip__icon",
                }}
                key={KEY}
                label={
                  <span title={getDisplayName(selectedVal)}>
                    {getDisplayName(selectedVal)}
                  </span>
                }
                onClick={handleChipClick}
                deleteIcon={
                  <div
                    tabIndex={0}
                    onClick={(e) => {
                      if (selectedVal !== nonClearableValue && !showMenu)
                        handleDelete(e, selectedVal);
                    }}
                    onKeyDown={(e) => {
                      if (
                        selectedVal !== nonClearableValue &&
                        !showMenu &&
                        (e.code === "Space" || e.code === "Enter")
                      )
                        handleDelete(e, selectedVal);
                    }}
                    aria-label="remove"
                    style={{ height: 24 }}
                  >
                    <XIcon />
                  </div>
                }
              />
            );
          })}
          {visibleValues.length < value.length && (
            <div className="margin-right margin-top">
              + {value.length - visibleValues.length} More
            </div>
          )}
          {typeAhead && (
            <input
              name={`${name}_typeahead`}
              onChange={handleTypeAhead}
              className={inputClass}
              autoComplete="off"
              value={filterText}
              ref={inputRef}
              onKeyDown={handleInputKeyDown}
              placeholder={placeholder && !hasValue ? placeholder : null}
              disabled={disabled}
            />
          )}
        </div>
        <div className="multiSelect__iconContainer">
          {hasValue && !showMenu && (
            <span
              tabIndex={0}
              aria-label={"Clear selections"}
              className="multiSelect__clearIcon"
              onClick={handleClear}
              onKeyDown={(e) => {
                if (e.code === "Enter" || e.code === "Space") {
                  handleClear(e);
                }
              }}
            >
              <XIcon />
            </span>
          )}
        </div>
      </div>
    );
  };

  const renderMenuItemChipIcon = (item) => {
    const { icon: IconComponent, description } = item;
    if (getCustomChipIcon?.(item)) {
      return getCustomChipIcon?.(item);
    } else
      return IconComponent ? (
        <span
          className={clsx(
            "multiSelect__optionIcon margin-right--half margin-left--half flex",
            description && "self-start",
          )}
        >
          <IconComponent />
        </span>
      ) : null;
  };

  const EllipisedMenuItem = ({ label }) => {
    const [showTooltip, setShowTooltip] = useState(false);
    return showTooltip ? (
      <Tooltip title={label}>
        <span className="multiSelect__label">{label}</span>
      </Tooltip>
    ) : (
      <span
        className="multiSelect__label"
        ref={(elem) => {
          if (elem?.offsetWidth < elem?.scrollWidth) {
            setShowTooltip(true);
          }
        }}
      >
        {label}
      </span>
    );
  };

  const renderMenuItems = () => {
    if (filteredValues.length === 0) {
      return <MenuItem>No values</MenuItem>;
    }

    return filteredValues.map((item, idx) => {
      const isSelected = referToId
        ? !!displayValues.find((val) => val.id === item.id)
        : value.indexOf(item.value) !== -1;

      const chip = renderMenuItemChipIcon(item);
      return (
        <MenuItem
          key={idx}
          value={item.value}
          onClick={() => handleMenuItemClick(item)}
          selected={isSelected}
          classes={{
            root: "multiSelect__option",
            selected: "multiSelect__option --selected",
          }}
          disabled={item.disabled}
        >
          <span
            className={clsx(
              "multiSelect__cbox",
              "pr-05",
              !item.description ? "" : ["self-start", "pt-05"],
            )}
          >
            <Checkbox checked={isSelected} />
          </span>
          <div className="multiSelect__label__container flex --alignCenter">
            {chip}
            {item.description ? (
              <div className="flex flex-col">
                <EllipisedMenuItem label={item.label} />
                <div
                  className="color-faded"
                  style={{ textWrap: "wrap", fontSize: "13px" }}
                >
                  {item.description}
                </div>
              </div>
            ) : (
              <EllipisedMenuItem label={item.label} />
            )}
          </div>
        </MenuItem>
      );
    });
  };

  return (
    <div className={rootClass}>
      {label && (
        <Label
          value={label}
          className={labelClass}
          id={`select-label-${name}`}
        />
      )}
      {renderValue()}
      <Menu
        anchorEl={valueContainerRef.current}
        open={showMenu}
        onClose={handleClose}
        autoFocus={false}
        disableAutoFocus
        disableEnforceFocus
        transitionDuration={{
          exit: 0,
        }}
        MenuListProps={{
          disablePadding: true,
          className: "multiSelect__menuList",
          sx: {
            width: valueContainerRef.current?.clientWidth,
          },
        }}
        PaperProps={{
          onScroll: handleScroll,
        }}
        container={valueContainerRef.current || document.body}
      >
        {renderMenuItems()}
      </Menu>
    </div>
  );
};

MultiSelectComponent.propTypes = {
  innerRef: PropTypes.any,
  classes: PropTypes.shape({
    root: PropTypes.string,
    value: PropTypes.string,
    input: PropTypes.string,
    label: PropTypes.string,
    nonClearableChip: PropTypes.string,
  }),
  value: PropTypes.array,
  options: PropTypes.arrayOf(
    PropTypes.shape({
      label: PropTypes.string,
      value: PropTypes.string,
    }),
  ).isRequired,
  handleChange: PropTypes.func,
  style: PropTypes.object,
  label: PropTypes.string,
  limitTags: PropTypes.number,
  name: PropTypes.string,
  form: PropTypes.object,
  typeAhead: PropTypes.bool,
  placeholder: PropTypes.string,
  loadNextRecords: PropTypes.func,
  onChange: PropTypes.func,
  displayValues: PropTypes.arrayOf(
    PropTypes.shape({
      label: PropTypes.string,
      value: PropTypes.string,
    }),
  ),
  disabled: PropTypes.bool,
  nonClearableValue: PropTypes.string,
  getCustomChipIcon: PropTypes.func,
  referToId: PropTypes.bool,
};

MultiSelectComponent.defaultProps = {
  classes: {},
  value: [],
  displayValues: [],
  style: {},
  label: null,
  name: "",
  typeAhead: true,
  hasChipIcon: false,
};

const MultiSelect = React.forwardRef((props, ref) => {
  return <MultiSelectComponent {...props} innerRef={ref} />;
});
export default MultiSelect;
