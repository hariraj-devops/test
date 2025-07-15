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
const datasetStyle = {
  border: "0.5px solid var(--border--neutral)",
  height: "auto",
  minHeight: "40px",
  width: "auto",
  fontSize: "12px",
  fontStyle: "normal",
  lineHeight: "18px",
  display: "flex",
  justifyContent: "center",
  alignItems: "center",
  borderRadius: "4px",
  overflowWrap: "anywhere",
  color: "var(--text--primary)",
};

export const getColorCode = (type) => {
  switch (type) {
    case "PHYSICAL_DATASET":
      return {
        ...datasetStyle,
        border: "0.5px solid var(--border--neutral)",
        borderLeft: "6px solid #A672BB",
        fontFamily: "var(--dremio--font-family)",
        fontWeight: "600",
        backgroundColor: "var(--fill--tertiary)",
      };
    case "VIRTUAL_DATASET":
      return {
        ...datasetStyle,
        border: "0.5px solid var(--border--neutral)",
        borderLeft: "6px solid #3ACBAC",
        fontFamily: "var(--dremio--font-family)",
        fontWeight: "600",
        backgroundColor: "var(--fill--tertiary)",
      };
    case "VIRTUAL_DATASET_NDS":
      return {
        ...datasetStyle,
        borderLeft: "6px solid rgba(150, 222, 207, 1)",
        fontFamily: "var(--dremio--font-family)",
        fontWeight: "normal",
        backgroundColor: "var(--fill--primary)",
      };
    case "PHYSICAL_DATASET_NDS":
      return {
        ...datasetStyle,
        borderLeft: "6px solid rgba(204, 178, 214, 1)",
        fontFamily: "var(--dremio--font-family)",
        fontWeight: "normal",
        backgroundColor: "var(--fill--primary)",
      };
    case "ALGEBRIC":
      return {
        ...datasetStyle,
        borderLeft: "6px solid #3ACBAC",
        border: "1.5px dotted var(--border--neutral)",
        fontFamily: "var(--dremio--font-family)",
        fontWeight: "normal",
        backgroundColor: "var(--fill--primary)",
      };
    case "OTHERS":
      return {
        ...datasetStyle,
        borderLeft: "6px solid var(--border--neutral)",
        fontFamily: "var(--dremio--font-family)",
        fontWeight: "600",
        backgroundColor: "var(--fill--tertiary)",
      };
    default:
      return {
        ...datasetStyle,
        border: "1.5px solid var(--border--neutral)",
        borderLeft: "6px solid #1B69C5",
        fontFamily: "var(--dremio--font-family)",
        fontWeight: "600",
        backgroundColor: "var(--fill--primary)",
      };
  }
};

export const initialElements = [
  {
    id: "1234",
    data: { label: "" },
    position: { x: 0, y: 0 },
    style: {
      border: "1px solid var(--border--neutral)",
      borderLeft: "8px solid var(--color--indigo--300)",
      width: "156px",
      height: "67px",
      backgroundColor: "var(--fill--primary)",
    },
  },
];

export const getSortedReflectionsData = (reflectionData) => {
  return reflectionData
    .sort((job) => (job.reflectionType === "RAW" ? -1 : 1))
    .sort((job) => (job.isUsed ? -1 : 1));
};
