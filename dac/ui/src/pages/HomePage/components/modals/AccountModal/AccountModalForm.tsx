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
import Immutable from "immutable";
import { withRouter } from "react-router";
import Info from "@inject/pages/AccountPage/subpages/InfoController";
import NavPanel from "#oss/components/Nav/NavPanel";
import AccountPreferencesPage from "@inject/pages/AccountPreferences";
import { accountSectionNavItems } from "#oss/pages/AccountPage/AccountPageConstants";
import * as classes from "./AccountModal.module.less";

type AccountModalFormProps = {
  location: any;
  router: any;
};

const AccountModalForm = (props: AccountModalFormProps) => {
  const { location, router } = props;
  const [tab, setTab] = useState("General Information");
  const tabs = Immutable.OrderedMap(
    [...accountSectionNavItems, ["Appearance", "Appearance"]].filter(Boolean),
  );

  const handleChangeTab = (tab: string) => {
    const confirm = () => {
      router.push({
        ...location,
        state: { ...location.state, tab },
      });
      setTab(tab);
    };
    confirm();
  };

  const renderContent = () => {
    if (tab === "General Information") {
      return <Info leftAlignFooter hideCancel />;
    } else if (tab === "Appearance") {
      return <AccountPreferencesPage />;
    }
  };

  return (
    <div className={classes["contentContainer"]}>
      <NavPanel
        changeTab={handleChangeTab}
        activeTab={tab}
        tabs={tabs}
        orientation="vertical"
      />
      <div className="main-content">{renderContent()}</div>
    </div>
  );
};

export default withRouter(AccountModalForm);
