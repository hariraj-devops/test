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

import React from "react";
import PropTypes from "prop-types";
import clsx from "clsx";

import "./modalFormActionContainer.scss";

const ModalFormActionContainer = (props) => {
  const { children, classes } = props;

  const rootClass = clsx([
    "modalFormActionContainer__root",
    { [classes.root]: classes.root },
  ]);
  const containerClass = clsx([
    "modalFormActionContainer__container",
    { [classes.root]: classes.root },
  ]);

  return (
    <div className={rootClass}>
      <div className={containerClass}>{children}</div>
    </div>
  );
};

ModalFormActionContainer.propTypes = {
  children: PropTypes.oneOfType([
    PropTypes.arrayOf(PropTypes.node),
    PropTypes.arrayOf(PropTypes.func),
    PropTypes.node,
    PropTypes.func,
  ]),
  classes: PropTypes.object,
  disableSpacing: PropTypes.bool,
};

ModalFormActionContainer.defaultProps = {
  classes: {},
};

export default ModalFormActionContainer;
