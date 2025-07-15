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
import clsx from "clsx";
import { constructFullPath } from "#oss/utils/pathUtils";
import { useCallback, useRef } from "react";
import Modal from "components/Modals/Modal";
import SelectContextForm from "../../forms/SelectContextForm";
import { TagContent } from "#oss/pages/HomePage/components/BranchPicker/components/BranchPickerTag/BranchPickerTag";
import { Spinner } from "dremio-ui-lib/components";
import {
  getContextValue,
  getCtxState,
  useCtxPickerActions,
  useInitializeNessieState,
} from "./utils";

import * as classes from "./ContextPicker.module.less";
import { useDispatch } from "react-redux";
import {
  resetRefs as resetRefsAction,
  type DatasetReference,
} from "#oss/actions/nessie/nessie";
import { useSelector } from "react-redux";
import { getHomeSource, getSortedSources } from "#oss/selectors/home";
import { useIsArsEnabled } from "@inject/utils/arsUtils";
import { clearResourceTree } from "#oss/actions/resources/tree";
import { getViewState } from "#oss/selectors/resources";
import { useFilterTreeArs } from "#oss/utils/datasetTreeUtils";
import {
  isLimitedVersionSource,
  useSourceTypeFromState,
} from "@inject/utils/sourceUtils";

type ContextPickerProps = {
  value: any;
  className?: string;
  onChange: (newValue: any) => void;
  disabled?: boolean;
};

export const ContextPicker = ({
  value: context,
  onChange,
  className,
  disabled,
}: ContextPickerProps) => {
  const sourceName = context?.get(0);
  const sourceType = useSourceTypeFromState(sourceName);

  useInitializeNessieState(sourceName); //Fetch default reference if required
  const [isArsLoading, isArsEnabled] = useIsArsEnabled();
  const filterTree = useFilterTreeArs();

  const dispatch = useDispatch();
  const resetRefs = useCallback(
    (refs: DatasetReference) => {
      resetRefsAction(refs)(dispatch);
    },
    [dispatch],
  );

  const isSourcesLoading = useSelector(
    (state) => getViewState(state, "AllSources")?.get("isInProgress") ?? true,
  );
  const homeSource = useSelector((state) =>
    getHomeSource(getSortedSources(state)),
  );
  const nessieState = useSelector((state: any) => state.nessie);
  const [refState, refLoading] = getCtxState({
    sourceName,
    nessieState,
  });
  const [show, open, close, cancel] = useCtxPickerActions({
    nessieState,
    resetRefs,
  });

  const ref = useRef<HTMLSpanElement>(null);
  const textValue = getContextValue(context);
  const title = refState?.hash || refState?.reference?.name;

  const Content = (
    <>
      <span
        title={textValue}
        className={clsx("branchPickerTag-labelDiv", classes["context"])}
      >
        {textValue}
      </span>
      <span className={classes["branch"]}>
        {refLoading ? (
          <Spinner />
        ) : (
          refState?.reference &&
          !isLimitedVersionSource(sourceType) && (
            <div {...(!!title && { title })} className={classes["contextTag"]}>
              <TagContent reference={refState.reference} hash={refState.hash} />
            </div>
          )
        )}
      </span>
    </>
  );

  // Workaround to prevent doube-fetch when expanding pre-existing nodes
  const clearResources = () => {
    dispatch(clearResourceTree({ fromModal: true }));
  };
  const handleCancel = () => {
    clearResources();
    cancel();
  };
  const handleClose = () => {
    clearResources();
    close();
  };

  return (
    <>
      <div className="sqlAutocomplete__context">
        <>
          <span className={classes["offset"]}>{laDeprecated("Context:")}</span>
          <span
            ref={ref}
            tabIndex={disabled ? -1 : 0}
            className={clsx(className, refLoading && classes["loadingWrapper"])}
            disabled={disabled}
            onClick={open}
            onKeyDown={(e) => {
              if (e.code === "Enter" || e.code === "Space") {
                open();
              }
            }}
            aria-label={"Context " + textValue}
          >
            {Content}
          </span>
        </>
      </div>
      {!isArsLoading && !isSourcesLoading && show && (
        <Modal isOpen hide={handleCancel} size="small" modalHeight="600px">
          <SelectContextForm
            onFormSubmit={(resource: any) => {
              onChange(resource.context);
              handleClose();
            }}
            onCancel={handleCancel}
            initialValues={{ context: constructFullPath(context) }}
            filterTree={filterTree}
            {...(isArsEnabled &&
              !!homeSource && {
                getPreselectedNode: (context: string) => {
                  return context.startsWith(homeSource.get("name")) ||
                    // SelectContextForm will send in `value.`, not sure why
                    context === `${homeSource.get("name")}.`
                    ? homeSource.get("name")
                    : context;
                },
              })}
          />
        </Modal>
      )}
    </>
  );
};
