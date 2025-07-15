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
import { useEffect, useState } from "react";
import { getFolder } from "#oss/endpoints/Folder/GetFolder/getFolder";
import UpdateUriFolderForm from "./UpdateUriFolderForm";
import { ErrorBoundary } from "#oss/components/ErrorBoundary/ErrorBoundary";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";

type UpdateUriFormWrapperProps = {
  entity: Record<string, any>;
  hide: () => void;
  containerStyle: Record<string, any>;
  source: Record<string, any>;
};
const UpdateUriFormWrapper = ({
  entity,
  hide,
  source,
}: UpdateUriFormWrapperProps) => {
  const { t } = getIntlContext();
  const [folder, setFolder] = useState(undefined);
  const [loading, setLoading] = useState(true);
  const rootName = entity.fullPathList[0];
  const fullPath = entity.fullPathList.slice(1).join("/");

  useEffect(() => {
    async function fetchData() {
      try {
        const data = await getFolder("source", rootName, fullPath);
        setFolder(data);
        setLoading(false);
      } catch {
        setLoading(false);
      }
    }
    fetchData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (loading) return null;
  return (
    folder && (
      <ErrorBoundary title={t("Common.Errors.Unexpected")}>
        <UpdateUriFolderForm entity={folder} hide={hide} source={source} />
      </ErrorBoundary>
    )
  );
};

export default UpdateUriFormWrapper;
