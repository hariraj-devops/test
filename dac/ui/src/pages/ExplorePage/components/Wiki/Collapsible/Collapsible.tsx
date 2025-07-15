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

import clsx from "clsx";
import { useState, ReactNode } from "react";
import * as classes from "./Collapsible.module.less";

type CollapsibleProps = {
  title: string;
  body: ReactNode;
  toolbar: {
    name: string;
    component: ReactNode;
    componentClass?: string;
  }[];
  bodyClass?: string;
  bodyStyle?: Record<string, any>;
  className?: string;
};

const Collapsible = ({
  title,
  body,
  toolbar,
  className,
  bodyClass,
  bodyStyle = {},
}: CollapsibleProps) => {
  const [open, setOpen] = useState(true);
  return (
    <div
      className={clsx(classes["collapsibleContainer"], className)}
      style={{ flex: open ? "1" : "0", ...bodyStyle }}
    >
      <div className={classes["collapsibleTitleRow"]}>
        <div
          className={classes["collapsibleTitleName"]}
          onClick={() => {
            setOpen(open ? false : true);
          }}
          tabIndex={0}
          role="button"
          onKeyDown={(e) => {
            if (e.code === "Enter" || e.code === "Space") {
              setOpen(open ? false : true);
            }
          }}
          aria-label={title}
          aria-expanded={open}
          aria-controls={title}
        >
          <dremio-icon
            name={open ? "interface/down-chevron" : "interface/up-chevron"}
            class={classes["collapsibleChevron"]}
          />
          <div className={classes["collapsibleTitle"]}>{title}</div>
        </div>
        <div className={classes["collapsibleToolbar"]}>
          {toolbar.map(
            (tool: {
              name: string;
              component: ReactNode;
              componentClass?: string;
            }) => {
              return (
                <div
                  key={tool.name}
                  className={classes[`${tool.componentClass}`]}
                >
                  <div
                    onClick={(e: {
                      stopPropagation: () => void;
                      preventDefault: () => void;
                    }) => {
                      e.stopPropagation();
                      e.preventDefault();
                      setOpen(true);
                    }}
                  >
                    {tool.component}
                  </div>
                </div>
              );
            },
          )}
        </div>
      </div>
      {open && (
        <div
          className={bodyClass ? bodyClass : classes["collapsibleBody"]}
          id={title}
        >
          {body}
        </div>
      )}
    </div>
  );
};

export default Collapsible;
