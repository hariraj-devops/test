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
import { useForm } from "react-hook-form";
import { useDispatch } from "react-redux";
import { TextInput } from "@mantine/core";
import { HoverHelp } from "dremio-ui-lib";
import Message from "#oss/components/Message";
import ConfirmCancelFooter from "#oss/components/Modals/ConfirmCancelFooter";
import { updateFolder } from "#oss/actions/resources/spaceDetails";
import {
  getUpdateFolderResourcePath,
  getPrefixFromPath,
  getDefaultStorageURI,
  isUsingCustomStorage,
  constructDefaultStorageURI,
  removePrefix,
} from "./uriFolderUtils";
import { ControlledRadio } from "./components/ControlledRadio";
import { WrapInputWithPrefix } from "./components/WrapInputWithPrefix";
import { addNotification } from "#oss/actions/notification";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";
type UpdateUriFolderFormProps = {
  entity: Record<string, any>;
  hide: () => void;
  source: Record<string, any>;
};

const UpdateUriFolderForm = ({
  entity,
  hide,
  source,
}: UpdateUriFolderFormProps) => {
  const { t } = getIntlContext();
  const dispatch = useDispatch();
  const [errorMessage, setErrorMessage] = useState<string>("");
  const rootName = entity.fullPathList[0];
  const entityName: string = entity.name;
  const resourcePath = getUpdateFolderResourcePath(entity.links.self);
  const prefix = getPrefixFromPath(entity.storageUri);
  const defaultStorageURI = getDefaultStorageURI(source);
  const isCustomStorage = isUsingCustomStorage(defaultStorageURI, entity);
  const customStorageUriWithoutPrefix = removePrefix(entity.storageUri);

  const defaultStorageType: string = isCustomStorage ? "custom" : "default";

  const {
    register,
    handleSubmit,
    watch,
    control,
    formState: { errors, isValid, isDirty, isSubmitting },
  } = useForm({
    mode: "onChange",
    defaultValues: {
      name: entityName,
      storageURI: customStorageUriWithoutPrefix,
      storageType: defaultStorageType,
    },
  });

  const submit = async ({
    name,
    storageURI,
    storageType,
  }: {
    name: string;
    storageURI: string;
    storageType: string;
  }) => {
    const payload = {
      name,
      storageUri:
        storageType === "default"
          ? constructDefaultStorageURI(defaultStorageURI, entity)
          : prefix + storageURI,
    };
    await dispatch(
      //@ts-ignore
      updateFolder(payload.name, resourcePath, rootName, payload.storageUri),
    )
      .then((res) => {
        if (res.error) {
          setErrorMessage(res.payload?.response?.errorMessage);
        } else {
          hide();
          dispatch(
            addNotification(
              t("Folder.Uri.Storage.Update.Success.Notification", { name }),
              "success",
            ),
          );
        }
        return;
      })
      .catch(() => {});
  };

  return (
    <form
      onSubmit={handleSubmit((values) => submit(values))}
      style={{ position: "relative", height: "100%" }}
    >
      <div className="p-2 mb-3">
        {errorMessage && <Message messageType="error" message={errorMessage} />}
        <TextInput
          label={t("Folder.Create.Name")}
          {...register("name", {
            required: t("Folder.Create.Name.Error"),
          })}
          id="name"
          error={errors?.name?.message}
          disabled
        />
        <div className="mt-3">
          <div className="mb-3" style={{ fontWeight: 600 }}>
            {t("Folder.Uri.Storage")}
          </div>
          <div className="flex items-center">
            <ControlledRadio
              name="storageType"
              control={control}
              defaultChecked={!isCustomStorage}
              label={t("Folder.Uri.Storage.Default.Label")}
              value="default"
            />
            <HoverHelp
              content={t("Folder.Uri.Storage.Default.Tooltip")}
              placement="top"
            />
          </div>
          <div className="mt-05 flex items-center">
            <ControlledRadio
              name="storageType"
              control={control}
              defaultChecked={isCustomStorage}
              label={t("Folder.Uri.Storage.Custom.Label")}
              value="custom"
              rules={{ deps: ["storageType"] }}
            />
            <HoverHelp
              content={t("Folder.Uri.Storage.Custom.Tooltip")}
              placement="top"
            />
          </div>
        </div>
        {watch("storageType") === "custom" && (
          <div className="mt-3">
            <label htmlFor="storageURI">
              <div className="mb-05">{t("Folder.Uri.Storage.StorageUri")}</div>
              <WrapInputWithPrefix
                prefix={prefix}
                error={!!errors?.storageURI?.message}
                errorMessage={errors?.storageURI?.message}
                children={
                  <TextInput
                    {...register("storageURI", {
                      required: t("Folder.Uri.Storage.Custom.Error"),
                    })}
                    defaultValue={customStorageUriWithoutPrefix}
                    placeholder="e.g. my-bucket/"
                    id="storage-custom"
                    error={!!errors?.storageURI?.message}
                  />
                }
              />
            </label>
          </div>
        )}
      </div>
      <ConfirmCancelFooter
        cancel={() => hide()}
        submitForm
        canSubmit={isValid && isDirty}
        submitting={isSubmitting}
        confirmText={t("Common.Actions.Save")}
        style={{ position: "absolute", bottom: 0 }}
      />
    </form>
  );
};

export default UpdateUriFolderForm;
