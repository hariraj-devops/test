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
import type { User } from "@dremio/dremio-js/oss";
import { Avatar, Tooltip } from "@dremio/design-system/components";
import { FC } from "react";
import { getIntlContext } from "../contexts/IntlContext";

export const DremioUserTooltip: FC<{ user: User }> = (props) => {
  const subheading = props.user.email || props.user.username;
  const showSubheading = props.user.displayName !== subheading;
  return (
    <div className="flex">
      <div className="flex flex-row gap-rel-075 items-center">
        {/* <Avatar initials={props.user.initials} /> */}
        <div className="flex flex-col text-left">
          <div className="text-semibold">{props.user.displayName}</div>
          {showSubheading && <div className="text-sm">{subheading}</div>}
        </div>
      </div>
    </div>
  );
};

export const DremioUser: FC<{ user: User }> = (props) => {
  return (
    <Tooltip content={<DremioUserTooltip user={props.user} />}>
      <div className="inline-flex flex-row gap-rel-075 items-center overflow-hidden">
        <Avatar initials={props.user.initials} />{" "}
        <span className="truncate">{props.user.displayName}</span>
      </div>
    </Tooltip>
  );
};

export const DremioUserAvatar: FC<{ user: User }> = (props) => {
  return (
    <Tooltip content={<DremioUserTooltip user={props.user} />}>
      <Avatar initials={props.user.initials} />
    </Tooltip>
  );
};

const NullDremioUserTooltip = () => {
  const { t } = getIntlContext();
  return (
    <div style={{ maxWidth: "35ch" }}>{t("User.Unavailable.Description")}</div>
  );
};

export const NullDremioUser: FC = () => {
  const { t } = getIntlContext();
  return (
    <div className="inline-flex flex-row gap-rel-075 items-center overflow-hidden">
      <NullDremioUserAvatar />
      <span className="dremio-typography-less-important truncate">
        {t("User.Unavailable.Title")}
      </span>
    </div>
  );
};

export const NullDremioUserAvatar: FC = () => {
  return (
    <Tooltip content={<NullDremioUserTooltip />}>
      <Avatar
        initials="?"
        style={{
          background: "var(--fill--disabled)",
          color: "var(--text--disabled)",
        }}
      />
    </Tooltip>
  );
};
