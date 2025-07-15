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

import { SupportKeyState } from "#oss/queries/flags";
import { supportKey, supportKeyMutation } from "@inject/queries/flags";
import { useQuery, useMutation } from "@tanstack/react-query";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";

export const useSupportKeyMutate = (key: string) => {
  const pid = getSonarContext().getSelectedProjectId?.();
  const supportKeyData = useQuery(supportKey(pid)(key));
  const supportKeyMutate = useMutation(supportKeyMutation(pid)(key));

  return {
    value: supportKeyData.data?.value as SupportKeyState["value"] | undefined,
    isLoading: supportKeyData.isLoading,
    setValue: (value: SupportKeyState["value"]) =>
      supportKeyMutate.mutate(value),
  };
};
