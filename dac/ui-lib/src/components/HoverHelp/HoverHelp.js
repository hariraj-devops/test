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
import { clsx } from "clsx";
import { default as Tooltip } from "../Tooltip/Tooltip.tsx";

import "./HoverHelp.scss";

const HoverHelp = (props) => {
  const { arrow, content, placement, className, container } = props;

  return (
    <div className={clsx("margin-left--half flex", className)}>
      <Tooltip
        arrow={arrow}
        placement={placement}
        title={content}
        PopperProps={{ container: container || document.body }}
      >
        <div className="iconContainer">
          <dremio-icon
            name="interface/information"
            alt="info"
            class="infoIcon"
          />
        </div>
      </Tooltip>
    </div>
  );
};

HoverHelp.defaultProps = {
  placement: "bottom",
};

HoverHelp.propTypes = {
  content: PropTypes.string.isRequired,
  placement: PropTypes.string,
  arrow: PropTypes.bool,
  className: PropTypes.string,
  container: PropTypes.node,
};

export default HoverHelp;
