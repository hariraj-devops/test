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
import Message from "#oss/components/Message";
import { HoverHelp } from "dremio-ui-lib";
import ConfirmCancelFooter from "#oss/components/Modals/ConfirmCancelFooter";
import { addNewFolderForSpace } from "#oss/actions/resources/spaceDetails";
import {
  getDefaultStorageURI,
  getPrefixFromPath,
  removePrefix,
} from "./uriFolderUtils";
import { addNotification } from "#oss/actions/notification";
import { ControlledRadio } from "./components/ControlledRadio";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";
import { WrapInputWithPrefix } from "./components/WrapInputWithPrefix";

type AddUriFolderFormProps = {
  entity: Record<string, any>;
  hide: () => void;
  source: Record<string, any>;
  isEditModal?: boolean;
};

const AddUriFolderForm = ({
  entity,
  hide,
  source,
  isEditModal,
}: AddUriFolderFormProps) => {
  const dispatch = useDispatch();
  const { t } = getIntlContext();
  const [errorMessage, setErrorMessage] = useState<string>("");
  const rootName = entity.fullPathList[0];
  const resourcePath = isEditModal ? entity.links.self : entity.resourcePath;
  const defaultPath = getDefaultStorageURI(source);
  const prefix = getPrefixFromPath(defaultPath);
  const {
    register,
    handleSubmit,
    control,
    watch,
    formState: { errors, isSubmitting, isValid },
  } = useForm({
    mode: "onChange",
    defaultValues: {
      name: "",
      storageURI: removePrefix(defaultPath),
      storageType: "default",
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
      storageUri: storageType === "default" ? null : prefix + storageURI,
    };
    await dispatch(
      //@ts-ignore
      addNewFolderForSpace(
        payload.name,
        resourcePath,
        rootName,
        payload.storageUri,
      ),
    )
      .then((res) => {
        if (res.error) {
          setErrorMessage(res.payload?.response?.errorMessage);
        } else {
          hide();
          dispatch(
            addNotification(
              t("Folder.Uri.Storage.Create.Success.Notification", { name }),
              "success",
            ),
          );
        }
        return;
      })
      .catch(() => {});
  };

  return (
    <form onSubmit={handleSubmit((values) => submit(values))}>
      <div className="p-2 mb-3">
        {errorMessage && <Message messageType="error" message={errorMessage} />}
        <TextInput
          label={t("Folder.Create.Name")}
          {...register("name", {
            required: t("Folder.Create.Name.Error"),
          })}
          id="name"
          error={errors?.name?.message}
        />
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
            <HoverHelp
              content={t("Folder.Uri.Storage.Default.Tooltip")}
              placement="top"
            />
          </div>
          <div className="mt-05 flex items-center">
            <ControlledRadio
              name="storageType"
              control={control}
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
        submitting={isSubmitting}
        canSubmit={isValid}
        confirmText={t("Common.Actions.Add")}
      />
    </form>
  );
};

export default AddUriFolderForm;
