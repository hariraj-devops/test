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
import { v4 as uuidv4 } from "uuid";

export const HISTORY_LOAD_DELAY = 2500;
export const MAIN_HEADER_HEIGHT = "55";
export const LEFT_TREE_WIDTH = "220";
export const SEARCH_BAR_WIDTH = "600";
export const SEARCH_BAR_HEIGHT = "600";
export const AUTO_PREVIEW_DELAY = 1000;
export const MAX_UPLOAD_FILE_SIZE = 500 * 1024 * 1024; // 500MB
export const EXPLORE_PROGRESS_STATES = ["STARTED", "NOT STARTED", "RUNNING"]; //TODO put back NOT_SUBMITTED when it's working
export const CONTAINER_ENTITY_TYPES = new Set([
  "HOME",
  "FOLDER",
  "SPACE",
  "SOURCE",
]);
export const DATASET_ENTITY_TYPES = new Set([
  "PHYSICAL_DATASET",
  "PHYSICAL_DATASET_HOME_FILE",
  "PHYSICAL_DATASET_SOURCE_FILE",
  "VIRTUAL_DATASET",
  "PHYSICAL_DATASET_SOURCE_FOLDER",
  "INVALID_DATASET_TYPE",
  "PHYSICAL_DATASET_HOME_FOLDER",
  "OTHERS",
]);

export const DATASET_SUMMARY_ENTITY_TYPES = new Set([
  "PHYSICAL_DATASET",
  "PHYSICAL_DATASET_HOME_FILE",
  "PHYSICAL_DATASET_SOURCE_FILE",
  "VIRTUAL_DATASET",
  "PHYSICAL_DATASET_SOURCE_FOLDER",
  "PHYSICAL_DATASET_HOME_FOLDER",
]);

export const HOME_SPACE_NAME = `@home-${uuidv4()}`; // better to have Symbol here, but there is several problems with it
export const MSG_CLEAR_DELAY_SEC = 3;

export const EXTRA_POPPER_CONFIG = {
  modifiers: {
    preventOverflow: {
      escapeWithReference: true,
    },
  },
};

export const ENTITY_TYPES = {
  home: "home",
  space: "space",
  source: "source",
  folder: "folder",
};

export const ENTITY_TYPES_LIST = [
  ENTITY_TYPES.home,
  ENTITY_TYPES.space,
  ENTITY_TYPES.source,
  ENTITY_TYPES.folder,
];

export const PROJECT_TYPE = {
  queryEngine: "QUERY_ENGINE",
};

export const CLIENT_TOOL_ID = {
  powerbi: "client.tools.powerbi",
  tableau: "client.tools.tableau",
  qlik: "client.tools.qlik",
  qlikEnabled: "support.dac.qlik",
};

export const sqlEditorTableColumns = [
  {
    key: "jobStatus",
    id: "jobStatus",
    label: "",
    disableSort: true,
    isSelected: true,
    width: 20,
    height: 40,
    flexGrow: "0",
    flexShrink: "0",
    isFixedWidth: true,
    isDraggable: true,
    headerClassName: "",
    disabledClick: false,
  },
  {
    key: "sql",
    id: "sql",
    label: "SQL",
    disableSort: true,
    isSelected: true,
    width: 400,
    height: 40,
    flexGrow: "0",
    flexShrink: "0",
    isFixedWidth: true,
    isDraggable: true,
    headerClassName: "",
    disabledClick: false,
  },
  {
    key: "acceleration",
    id: "acceleration",
    label: "Accelerated",
    disableSort: true,
    isSelected: true,
    width: 40,
    height: 40,
    flexGrow: "0",
    flexShrink: "0",
    isFixedWidth: true,
    isDraggable: false,
    headerClassName: "",
    disabledClick: false,
  },
  {
    key: "qt",
    id: "qt",
    label: "Query Type",
    disableSort: true,
    width: 113,
    isSelected: true,
    height: 40,
    flexGrow: "0",
    flexShrink: "0",
    isFixedWidth: true,
    isDraggable: true,
    headerClassName: "",
    disabledClick: false,
  },
  {
    key: "st",
    id: "st",
    label: "Start Time",
    disableSort: true,
    width: 152,
    isSelected: true,
    height: 40,
    flexGrow: "0",
    flexShrink: "0",
    isFixedWidth: true,
    isDraggable: true,
    headerClassName: "",
    disabledClick: false,
  },
  {
    key: "dur",
    id: "dur",
    label: "Duration",
    disableSort: true,
    width: 95,
    isSelected: true,
    height: 40,
    flexGrow: "0",
    flexShrink: "0",
    isFixedWidth: true,
    isDraggable: true,
    headerClassName: "jobsContent__tableHeaders",
    disabledClick: false,
    columnAlignment: "alignRight",
  },
  {
    key: "job",
    id: "job",
    label: "Job ID",
    textEllipsesAlignedLeft: true,
    disableSort: true,
    isSelected: true,
    width: 122,
    height: 40,
    flexGrow: "0",
    flexShrink: "0",
    isFixedWidth: true,
    isDraggable: true,
    headerClassName: "",
    disabledClick: false,
  },
  {
    key: "buttons",
    id: "buttons",
    label: "",
    disableSort: true,
    isSelected: false,
    width: 72,
    height: 40,
    flexGrow: "0",
    flexShrink: "0",
    isFixedWidth: false,
    isDraggable: false,
    headerClassName: "",
    disabledClick: true,
  },
];
export const pendingSQLJobs = [
  {
    key: "jobStatus",
    id: "jobStatus",
    label: "",
    disableSort: true,
    isSelected: true,
    width: 20,
    height: 40,
    flexGrow: "0",
    flexShrink: "0",
    isFixedWidth: true,
    isDraggable: false,
    headerClassName: "",
    disabledClick: false,
  },
  {
    key: "sql",
    id: "sql",
    label: "",
    disableSort: true,
    isSelected: true,
    width: 973,
    height: 40,
    flexGrow: "0",
    flexShrink: "0",
    isFixedWidth: true,
    isDraggable: true,
    headerClassName: "",
    disabledClick: false,
  },
  {
    key: "buttons",
    id: "buttons",
    label: "",
    disableSort: true,
    isSelected: false,
    width: 72,
    height: 40,
    flexGrow: "0",
    flexShrink: "0",
    isFixedWidth: false,
    isDraggable: false,
    headerClassName: "",
    disabledClick: true,
  },
];

export const ScansForFilter = [
  {
    key: "datasetType",
    label: "Scans.SourceType",
    content: "Managed Reflection(Parque)",
  },
  { key: "nrScanThreads", label: "Scans.ScanThread", content: "115" },
  {
    key: "ioWaitDurationMs",
    label: "Scans.IoWaitTime",
    content: "00:00:00.75",
  },
  { key: "nrScannedRows", label: "Scans.RowScanned", content: "143K" },
];

export const EXECUTION_DROPDOWN_OPTIONS = [
  { option: "Runtime", label: "Runtime" },
  { option: "Total Memory", label: "Total Memory" },
  { option: "Records Processed", label: "Records Processed" },
];
