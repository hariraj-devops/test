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
import { useState } from "react";
import Immutable from "immutable";
import classNames from "clsx";
import { LeftTreeContent } from "@inject/pages/HomePage/components/LeftTree/components/LeftTreeContent";
import { onScroll } from "./LeftTreeUtils";
import { LeftTreeHeader } from "./components/LeftTreeHeader/LeftTreeHeader";
import { AddSourceButton } from "./components/AddSourceButton/AddSourceButton";
import "./LeftTree.less";

type LeftTreeProps = {
  sources: Immutable.List<Record<string, any>>;
  sourcesViewState: Immutable.Map<any, any>;
  currentProject: string | null;
  homeSourceUrl: string | null;
  className?: string;
  sourceTypesIncludeS3?: boolean;
};

const LeftTree = (props: LeftTreeProps) => {
  const {
    className,
    sources,
    sourcesViewState,
    currentProject,
    homeSourceUrl,
  } = props;
  const [addTopShadow, setAddTopShadow] = useState<boolean>(false);
  const [addBotShadow, setAddBotShadow] = useState<boolean>(false);
  const classes = classNames("left-tree", "left-tree-holder", className);

  return (
    <div className={classes}>
      <LeftTreeHeader
        addTopShadow={addTopShadow}
        currentProject={currentProject}
      />
      <div
        className="scrolling-container"
        onScroll={(e) => onScroll(e, setAddTopShadow, setAddBotShadow)}
      >
        <LeftTreeContent
          sources={sources}
          sourcesViewState={sourcesViewState}
          homeSourceUrl={homeSourceUrl}
        />
      </div>
      <AddSourceButton addBotShadow={addBotShadow} />
    </div>
  );
};

export default LeftTree;
