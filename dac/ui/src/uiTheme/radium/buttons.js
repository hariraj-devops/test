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
import { bodySmall } from "./typography";

export const button = {
  outline: "none",
  textDecoration: "none",
  border: "1px solid var(--border--neutral--solid)",
  padding: "0 6px",
  position: "relative",
  lineHeight: "30px",
  justifyContent: "space-around",
  alignItems: "center",
  textAlign: "center",
  minWidth: 100,
  height: "32px",
  borderRadius: 4,
  marginRight: 5,
  fontSize: 14,
  cursor: "pointer",
  display: "flex",
  fontWeight: 500,
};

export const primary = {
  ...button,
  color: "#fff",
  backgroundColor: "var(--color--brand--300)",
  border: "1px solid var(--color--brand--300)",
};

export const danger = {
  ...button,
  color: "#fff",
  backgroundColor: "var(--fill--danger--solid)",
  border: "none",
};

export const warn = {
  ...button,
  color: "#E46363",
  backgroundColor: "#FEEAEA",
  border: "none",
};

export const outlined = {
  ...button,
  color: "var(--color--brand--300)",
  backgroundColor: "inherit",
  borderColor: "var(--color--brand--300)",
};

export const disabled = {
  // todo: DRY with text field
  ...button,
  color: "var(--text--disabled)",
  backgroundColor: "var(--fill--disabled)",
  border: "none",
  cursor: "default",
};

export const submitting = {
  primary: {
    backgroundColor: "#68C6D3",
    cursor: "default",
  },
  secondary: {
    backgroundColor: "rgba(0,0,0,0.02)",
    cursor: "default",
  },
  danger: {
    backgroundColor: "var(--fill--danger--bold)",
    cursor: "default",
  },
};

export const disabledLink = {
  color: "#B2B2B2",
  backgroundColor: "rgba(0,0,0,0.08)",
  cursor: "default",
  pointerEvents: "none",
};

export const secondary = {
  ...button,
  color: "var(--text--primary)",
  backgroundColor: "white",
  border: `1px solid var(--border--neutral--solid)`,
};

export const inline = {
  ...secondary,
  height: 24,
};

export const sqlEditorButton = {
  ...bodySmall,
  height: 26,
  lineHeight: "auto",
  backgroundColor: "transparent",
  border: 0,
  minWidth: 60,
  marginRight: 5, // todo get rid of that
  borderRadius: 2,
  cursor: "pointer",
  padding: "0 6px 0 0",
  marginBottom: 0,
};
