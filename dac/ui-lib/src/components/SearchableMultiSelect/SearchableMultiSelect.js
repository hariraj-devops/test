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
import { get, debounce } from "lodash";
import Checkbox from "@mui/material/Checkbox";
import Chip from "@mui/material/Chip";
import Menu from "@mui/material/Menu";
import MenuItem from "@mui/material/MenuItem";

import { ReactComponent as XIcon } from "../../art/XLarge.svg";

import Label from "../Label";

import "./SearchableMultiSelect.scss";

const SearchableMultiSelect = (props) => {
  const {
    classes,
    form: { errors, touched, setFieldValue } = {},
    handleChange,
    label,
    limitTags,
    field: { name, value } = {},
    options,
    placeholder,
    typeAhead,
    onSearchChange,
    disabled,
  } = props;

  const [showMenu, setShowMenu] = useState(false);
  const [filterText, setFilterText] = useState("");
  const inputRef = useRef(null);
  const valueContainerRef = useRef(null);
  const debounceFn = useRef(debounce(handleDebounceFn, 1000));

  const visibleValues = useMemo(
    () => (limitTags && !showMenu ? value.slice(0, limitTags) : value),
    [value, limitTags, showMenu],
  );

  const hasError = get(touched, name) && get(errors, name);
  const rootClass = clsx("SearchableMultiSelect", {
    [classes.root]: classes.root,
  });
  const valueClass = clsx(
    "SearchableMultiSelect__value",
    { "--error": hasError },
    { "--disabled": disabled },
    { [classes.value]: classes.value },
  );

  const inputClass = clsx("SearchableMultiSelect__input", "margin-top", {
    [classes.input]: classes.input,
  });

  const labelClass = clsx("SearchableMultiSelect__label", {
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
    const updatedValue = value.filter(
      (selectedVal) => selectedVal.value !== deleteValue,
    );
    updateValue(updatedValue);
  };

  const addValue = (addedLabel, addedValue) => {
    const updatedValue = [...value, { label: addedLabel, value: addedValue }];
    updateValue(updatedValue);
  };

  const handleDelete = (event, deleteValue) => {
    removeValue(deleteValue);
    event.stopPropagation();
  };

  const handleOpen = () => {
    setShowMenu(true);
    inputRef.current.focus();
  };

  const handleClose = () => {
    setShowMenu(false);
    inputRef.current.blur(); // Todo: This doesn't work for some reason. Needs a fix.
  };

  const handleMenuItemClick = (selectedLabel, selectedValue) => {
    const isValueExist = value.find(
      (valueObject) => valueObject.value === selectedValue,
    );
    if (!isValueExist) {
      addValue(selectedLabel, selectedValue);
    } else {
      removeValue(selectedValue);
    }
    setFilterText("");
  };

  const handleChipClick = (e) => {
    e.stopPropagation();
  };

  const handleTypeAhead = (e) => {
    const searchText = e.currentTarget.value;
    setFilterText(searchText);
    debounceFn.current(searchText.trim());
  };

  function handleDebounceFn(searchText) {
    onSearchChange(searchText);
  }

  const handleInputKeyDown = (e) => {
    if (e.key === "Escape") {
      handleClose();
      e.stopPropagation();
      return;
    }

    const noFilterText = !filterText || filterText === "";
    if (noFilterText && value && value.length > 0 && e.key === "Backspace") {
      removeValue(value[value.length - 1]);
    }

    if (
      !noFilterText &&
      options.length === 1 &&
      e.key === "Enter" &&
      value.findIndex(
        (selectedVal) =>
          selectedVal.value.toLowerCase() === options[0].value.toLowerCase(),
      ) === -1
    ) {
      addValue(options[0].label, options[0].value);
      setFilterText("");
    }

    if (!showMenu && e.key !== "Tab" && e.key !== "Shift") {
      setShowMenu(true);
    }
  };

  const handleClear = (e) => {
    updateValue([]);
    e.stopPropagation();
  };

  const renderValue = () => {
    const hasValue = value && value.length > 0;
    return (
      <div ref={valueContainerRef} className={valueClass} onClick={handleOpen}>
        <div className="SearchableMultiSelect__inputContainer">
          {visibleValues.map((selectedVal) => (
            <Chip
              classes={{
                root: "multiSelect__chip",
                icon: "icon --md multiSelect__chip__icon",
              }}
              key={selectedVal.value}
              label={selectedVal.label}
              onClick={handleChipClick}
              onDelete={(ev) => handleDelete(ev, selectedVal.value)}
              deleteIcon={
                <div
                  tabIndex={0}
                  onClick={(ev) => handleDelete(ev, selectedVal.value)}
                  onKeyDown={(e) => {
                    if (e.code === "Space" || e.code === "Enter")
                      handleDelete(e, selectedVal.value);
                  }}
                  aria-label="remove"
                  style={{ height: 24 }}
                >
                  <XIcon />
                </div>
              }
            />
          ))}
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
              value={filterText}
              ref={inputRef}
              onKeyDown={handleInputKeyDown}
              autoComplete="off"
              placeholder={placeholder && !hasValue ? placeholder : null}
              disabled={disabled}
            />
          )}
        </div>
        <div className="SearchableMultiSelect__iconContainer">
          {hasValue && (
            <span
              className="SearchableMultiSelect__clearIcon"
              tabIndex={0}
              aria-label={"Clear selections"}
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

  const renderMenuItems = () => {
    if (options.length === 0) {
      return <MenuItem>No values</MenuItem>;
    }
    return options.map(({ label: optionLabel, value: optionValue }, idx) => {
      const isSelected =
        value.findIndex((option) => option.value === optionValue) !== -1;
      return (
        <MenuItem
          key={idx}
          value={optionValue}
          onClick={() => handleMenuItemClick(optionLabel, optionValue)}
          selected={isSelected}
          classes={{
            root: "SearchableMultiSelect__option",
            selected: "SearchableMultiSelect__option --selected",
          }}
        >
          {/* Todo: Use font icons for checkboxes */}
          <Checkbox
            checked={isSelected}
            color="primary"
            classes={{
              root: "SearchableMultiSelect__optionCheckbox gutter--none mr-1",
            }}
          />
          {optionLabel}
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
          className: "SearchableMultiSelect__menuList",
          sx: {
            width: valueContainerRef.current?.clientWidth,
          },
        }}
        container={valueContainerRef.current || document.body}
      >
        {renderMenuItems()}
      </Menu>
    </div>
  );
};

SearchableMultiSelect.propTypes = {
  classes: PropTypes.shape({
    root: PropTypes.string,
    value: PropTypes.string,
    input: PropTypes.string,
    label: PropTypes.string,
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
  field: PropTypes.object,
  typeAhead: PropTypes.bool,
  placeholder: PropTypes.string,
  enableSearch: PropTypes.bool,
  onSearchChange: PropTypes.func,
  disabled: PropTypes.bool,
};

SearchableMultiSelect.defaultProps = {
  classes: {},
  value: [],
  style: {},
  label: null,
  name: "",
  typeAhead: true,
  enableSearch: true,
  disabled: false,
};

export default SearchableMultiSelect;
