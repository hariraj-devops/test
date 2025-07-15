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
import { useSupportFlag } from "#oss/exports/endpoints/SupportFlags/getSupportFlag";
import { DATAPLANE_STORAGE_SELECTION_UI_ENABLED } from "#oss/exports/endpoints/SupportFlags/supportFlagConstants";
import ContainerSelection from "components/Forms/ContainerSelection";

export default function StorageSelectionWrapper({
  disabled,
  elementConfig,
  fields,
}: {
  disabled: boolean;
  elementConfig: Record<string, any>;
  fields: Record<string, any>;
}) {
  const [enabled, loading] = useSupportFlag(
    DATAPLANE_STORAGE_SELECTION_UI_ENABLED,
  );

  return (
    <ContainerSelection
      disabled={disabled}
      fields={fields}
      elementConfig={elementConfig}
      hidden={loading || !enabled}
    />
  );
}
