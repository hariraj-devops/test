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
import Immutable from "immutable";
import { useState, useContext } from "react";
import { useDispatch, useSelector } from "react-redux";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";
import {
  Button,
  ModalContainer,
  DialogContent,
  IconButton,
} from "dremio-ui-lib/components";
import Message from "#oss/components/Message";
import {
  handleAddFolder,
  type versionContext,
} from "#oss/endpoints/Folder/AddFolder/AddFolder";
import { useForm } from "react-hook-form";
import { TextInput } from "@mantine/core";
import { constructFullPath } from "#oss/utils/pathUtils";
import { loadResourceTree } from "#oss/actions/resources/tree";
import {
  RESOURCE_TREE_VIEW_ID,
  LOAD_RESOURCE_TREE,
} from "#oss/components/Tree/resourceTreeUtils";
import { TreeConfigContext } from "../Tree/treeConfigContext";
import { WrapInputWithPrefix } from "#oss/pages/HomePage/components/forms/UriFolder/components/WrapInputWithPrefix";
import { ControlledRadio } from "#oss/pages/HomePage/components/forms/UriFolder/components/ControlledRadio";
import {
  getDefaultStorageURI,
  getPrefixFromPath,
  removePrefix,
} from "#oss/pages/HomePage/components/forms/UriFolder/uriFolderUtils";
import { getSupportFlags } from "#oss/selectors/supportFlags";
import { STORAGE_URI_FOLDER } from "@inject/endpoints/SupportFlags/supportFlagConstants";
import { Tooltip } from "@dremio/design-system/components";
import {
  isURISupportedSource,
  getSourceFromState,
  isLimitedVersionSource,
} from "@inject/utils/sourceUtils";
import * as classes from "./AddFolderDialog.module.less";

type AddFolderDialogProps = {
  open: boolean;
  close: () => void;
  node: Immutable.Map<string, any>;
  preselectedNodeId: string;
  selectedVersionContext: versionContext;
  isArsEnabled: boolean;
};
export const AddFolderDialog = ({
  open,
  close,
  node,
  preselectedNodeId,
  selectedVersionContext,
  isArsEnabled,
}: AddFolderDialogProps): JSX.Element => {
  const { t } = getIntlContext();
  const [error, setError] = useState(undefined);
  const rootName = node?.get("fullPath").first();
  const isStorageUriEnabled = useSelector((state) => {
    return getSupportFlags(state)[STORAGE_URI_FOLDER];
  });
  const source = useSelector((state: Record<string, any>) => {
    return getSourceFromState(state.resources.entities.get("source"), rootName);
  });
  const sourceType = source && source.get("type");
  const isUriSupported = sourceType && isURISupportedSource(sourceType);
  const defaultPath = source && getDefaultStorageURI(source);
  const prefix = defaultPath && getPrefixFromPath(defaultPath);

  const fullPath = node?.get("fullPath")?.toJS() || [
    preselectedNodeId.replace(/['"]+/g, ""),
  ];
  const rootType = () => {
    if (node) {
      return node.get("rootType").toLowerCase();
    } else {
      return isArsEnabled ? "source" : "home";
    }
  };
  const dispatch = useDispatch();
  const { resourceTreeControllerRef } = useContext(TreeConfigContext);
  const {
    register,
    handleSubmit,
    control,
    watch,
    formState: { isValid, isSubmitting, errors },
  } = useForm({
    mode: "onChange",
    defaultValues: {
      name: "",
      storageURI: defaultPath ? removePrefix(defaultPath) : "",
      storageType: "default",
    },
  });

  const onSubmit = async ({
    name,
    storageType,
    storageURI,
  }: {
    name: string;
    storageType: string;
    storageURI: string;
  }) => {
    const rootName = fullPath[0];
    const folderPath = fullPath.slice(1).join("/");
    const constructedPath = constructFullPath([...fullPath]);
    const createdFolderPath = constructFullPath([...fullPath, name]);
    const payload = isUriSupported
      ? {
          name,
          storageUri: storageType === "default" ? null : prefix + storageURI,
        }
      : { name };
    try {
      await handleAddFolder(
        rootType(),
        rootName,
        folderPath,
        payload,
        selectedVersionContext,
      );
      const treeResponse = await dispatch(
        loadResourceTree(
          LOAD_RESOURCE_TREE,
          RESOURCE_TREE_VIEW_ID,
          constructedPath,
          {
            showDatasets: false,
            showSources: true,
            showSpaces: true,
            showHomes: true,
          },
          true,
          node,
          true,
        ) as any,
      );
      if (treeResponse && resourceTreeControllerRef) {
        resourceTreeControllerRef.current.handleSelectedNodeChange(
          createdFolderPath,
        );
        resourceTreeControllerRef.current.expandPathToSelectedNode(
          createdFolderPath,
        );
        close();
      }
    } catch (e: any) {
      setError(e?.responseBody?.errorMessage);
    }
  };
  const renderVersionContext = () => {
    if (
      !isLimitedVersionSource(sourceType) &&
      selectedVersionContext?.refValue
    ) {
      return (
        <span>
          <dremio-icon
            name="vcs/branch"
            alt="branch"
            class={`${classes["icon"]} mx-05`}
          ></dremio-icon>
          {selectedVersionContext.refValue}
        </span>
      );
    }
  };
  return (
    <ModalContainer open={() => {}} isOpen={open} close={close}>
      <form
        className={classes["form"]}
        onSubmit={(e) => {
          e.stopPropagation();
          handleSubmit(onSubmit)(e);
        }}
      >
        <DialogContent
          toolbar={
            <IconButton onClick={close} aria-label="close dialog">
              <dremio-icon name="interface/close-big" alt="" />
            </IconButton>
          }
          error={error && <Message message={error} messageType="error" />}
          className={classes["add-folder-dialog"]}
          title={t("Folder.Create.Modal.Title")}
          actions={
            <>
              <Button
                variant="secondary"
                onClick={close}
                disabled={isSubmitting}
              >
                {t("Common.Actions.Cancel")}
              </Button>
              <Button
                variant="primary"
                type="submit"
                disabled={!isValid}
                pending={isSubmitting}
              >
                {t("Common.Actions.Add")}
              </Button>
            </>
          }
        >
          <p>{t("Add.To")}</p>
          <p className="mb-3 mt-1 dremio-typography-less-important">
            {fullPath[fullPath.length - 1]}
            {renderVersionContext()}
          </p>
          <TextInput
            label={t("Folder.Create.Name")}
            {...register("name", {
              required: t("Folder.Create.Name.Error"),
            })}
            error={errors?.name?.message}
            className="mt-1"
          />
          {isUriSupported && isStorageUriEnabled && (
            <div className="mt-3">
              <div className="mb-3" style={{ fontWeight: 600 }}>
                {t("Folder.Uri.Storage")}
              </div>
              <div className="flex items-center">
                <ControlledRadio
                  name="storageType"
                  control={control}
                  defaultChecked
                  label={t("Folder.Uri.Storage.Default.Label")}
                  value="default"
                />
                <Tooltip
                  content={t("Folder.Uri.Storage.Default.Tooltip")}
                  placement="top"
                >
                  <dremio-icon
                    class="icon-primary-with-hover ml-05"
                    name="interface/warning"
                  />
                </Tooltip>
              </div>
              <div className="mt-05 flex items-center">
                <ControlledRadio
                  name="storageType"
                  control={control}
                  label={t("Folder.Uri.Storage.Custom.Label")}
                  rules={{ deps: ["storageType"] }}
                  value="custom"
                />
                <Tooltip
                  content={t("Folder.Uri.Storage.Custom.Tooltip")}
                  placement="top"
                >
                  <dremio-icon
                    class="icon-primary-with-hover ml-05"
                    name="interface/warning"
                  />
                </Tooltip>
              </div>
            </div>
          )}
          {watch("storageType") === "custom" && (
            <div className="mt-3">
              <label htmlFor="storageURI">
                <div className="mb-05">
                  {t("Folder.Uri.Storage.StorageUri")}
                </div>
                <WrapInputWithPrefix
                  prefix={prefix}
                  error={!!errors?.storageURI?.message}
                  errorMessage={errors?.storageURI?.message}
                  children={
                    <TextInput
                      {...register("storageURI", {
                        required: t("Folder.Uri.Storage.Custom.Error"),
                      })}
                      placeholder="e.g. my-bucket/"
                      id="storage-custom"
                      error={!!errors?.storageURI?.message}
                    />
                  }
                />
              </label>
            </div>
          )}
        </DialogContent>
      </form>
    </ModalContainer>
  );
};
