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

import { Controller, Control } from "react-hook-form";
import { Radio } from "dremio-ui-lib/components";

type ControlledRadioProps = {
  name: "storageType" | "name" | "storageURI";
  label: string;
  value: string;
  control: Control<
    {
      name: string;
      storageURI: string;
      storageType: string;
    },
    any
  >;
  defaultChecked?: boolean;
  rules?: Record<string, any>;
};
export const ControlledRadio = ({
  name,
  label,
  value,
  defaultChecked = false,
  control,
  rules = {},
}: ControlledRadioProps) => {
  return (
    <Controller
      name={name}
      control={control}
      rules={rules}
      render={({ field }) => {
        return (
          <Radio
            {...field}
            defaultChecked={defaultChecked}
            label={label}
            value={value}
          />
        );
      }}
    />
  );
};
