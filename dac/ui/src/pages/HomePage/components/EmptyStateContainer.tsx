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

import classNames from "clsx";
import { intl } from "#oss/utils/intl";
import LinkWithRef from "#oss/components/LinkWithRef/LinkWithRef";
import * as classes from "./EmptyStateContainer.module.less";

type EmptyStateContainerProps = {
  title?: string;
  className?: string;
  icon?: string;
  linkInfo?: {
    href: string;
    "data-qa": string;
    label: string;
    className?: string;
  };
  children?: React.ReactNode;
  titleValues?: any;
  titleClassName?: string;
};

const EmptyStateContainer = (props: EmptyStateContainerProps) => {
  const {
    title,
    className,
    icon,
    linkInfo,
    children,
    titleValues,
    titleClassName,
  } = props;
  const { formatMessage } = intl;
  return (
    <div
      className={classNames(
        classes["empty-state-container"],
        className,
        "empty-state-container",
      )}
    >
      {icon && <dremio-icon class={classes["empty-state-icon"]} name={icon} />}
      {title && (
        <span
          className={classNames(classes["empty-state-title"], titleClassName)}
        >
          {formatMessage({ id: title }, titleValues)}
        </span>
      )}
      {linkInfo && (
        // @ts-ignore
        <LinkWithRef
          to={linkInfo.href}
          data-qa={linkInfo["data-qa"]}
          className={linkInfo["className"]}
        >
          {formatMessage({ id: linkInfo.label })}
        </LinkWithRef>
      )}
      {children && children}
    </div>
  );
};

export default EmptyStateContainer;
