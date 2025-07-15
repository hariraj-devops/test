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

import { useIntl } from "react-intl";
import { SearchField } from "#oss/components/Fields";
import { useNessieContext } from "#oss/pages/NessieHomePage/utils/context";
import { debounce } from "lodash";

import * as classes from "./VersionedPageTableHeader.module.less";
import clsx from "clsx";

type VersionedPageTableHeaderProps = {
  placeholder: string;
  onSearchChange: (val: string) => void;
  leftButton?: JSX.Element;
  rightButton?: JSX.Element;
  name?: string;
  loading?: boolean;
};

function VersionedPageTableHeader({
  placeholder = "Dataset.Search",
  onSearchChange,
  leftButton,
  rightButton,
  name,
  loading,
}: VersionedPageTableHeaderProps): JSX.Element {
  const intl = useIntl();
  const nessieCtx = useNessieContext();

  const debounceSearch = debounce((val: string) => {
    onSearchChange(val);
  }, 250);

  return (
    <div
      className={clsx(
        classes["versioned-page-table-header"],
        "source-table-header",
      )}
    >
      <span className={classes["versioned-page-table-header__left"]}>
        <span className={classes["versioned-page-table-header__name"]}>
          {name || nessieCtx.source.name}
        </span>
      </span>
      <span className={classes["versioned-page-table-header__right"]}>
        {leftButton}
        <SearchField
          placeholder={intl.formatMessage({ id: placeholder })}
          onChange={debounceSearch}
          showCloseIcon
          showIcon
          className={classes["versioned-search-box"]}
          loading={loading}
        />
        {rightButton}
      </span>
    </div>
  );
}

export default VersionedPageTableHeader;
