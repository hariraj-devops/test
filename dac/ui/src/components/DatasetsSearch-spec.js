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
import { shallow } from "enzyme";
import Immutable from "immutable";
import * as sqlPaths from "dremio-ui-common/paths/sqlEditor.js";

import DatasetsSearch from "./DatasetsSearch";

describe("DatasetsSearch-spec", () => {
  let commonProps;
  beforeEach(() => {
    commonProps = {
      searchData: Immutable.fromJS([
        {
          fullPath: ["@test_user", "business_review"],
          displayFullPath: ["@test_user", "business_review"],
          context: ["@test_user"],
          parents: [
            {
              datasetPathList: ["@test_user", "business_review"],
              type: "VIRTUAL_DATASET",
            },
          ],
          fields: [
            { name: "A", type: "BIGINT" },
            { name: "B", type: "BIGINT" },
          ],
          datasetType: "VIRTUAL_DATASET",
          links: {
            edit: '/space/"@test_user"/"business_review"?mode=edit',
            self: '/space/"@test_user"/"business_review"',
          },
        },
        {
          fullPath: ["@test_user", "foo"],
          displayFullPath: ["@test_user", "foo"],
          context: ["@test_user"],
          parents: [
            {
              datasetPathList: ["@test_user", "foo"],
              type: "VIRTUAL_DATASET",
            },
          ],
          fields: [
            { name: "A", type: "BIGINT" },
            { name: "B", type: "BIGINT" },
          ],
          datasetType: "VIRTUAL_DATASET",
          links: {
            self: '/space/"@test_user"/"foo"',
            edit: '/space/"@test_user"/"foo"?mode=edit',
          },
        },
      ]),
      visible: true,
      globalSearch: true,
      searchViewState: Immutable.fromJS({ isInProgress: false }),
      inputValue: "foo",
      handleSearchHide: () => {},
    };
  });

  it("render elements", () => {
    const wrapper = shallow(<DatasetsSearch {...commonProps} />);
    expect(wrapper.find(".datasets-search")).have.length(1);
    expect(wrapper.find(".dataset-wrapper")).have.length(1);
    expect(
      wrapper.find(".dataset-wrapper").find("Connect(ViewStateWrapper)"),
    ).to.have.length(1);
  });

  it("renders bad data without exploding", () => {
    const wrapper = shallow(
      <DatasetsSearch {...commonProps} searchData={Immutable.List()} />,
    );
    expect(wrapper.find(".datasets-search")).have.length(1);
  });

  it("check content", () => {
    const wrapper = shallow(<DatasetsSearch {...commonProps} />);
    const dataset = wrapper.find(".dataset-wrapper").find(".dataset");
    const mainSettingsBtn = dataset.at(1).find(".main-settings-btn");

    const firstFullPath = JSON.stringify(
      commonProps.searchData.get(0).get("fullPath")?.toJS(),
    );

    const secondFullPath = JSON.stringify(
      commonProps.searchData.get(1).get("fullPath")?.toJS(),
    );
    const firstTo = {
      pathname: sqlPaths.sqlEditor.link(),
      search: `?context="${encodeURIComponent(
        "@test_user",
      )}"&queryPath=${encodeURIComponent(firstFullPath)}`,
    };

    const secondTo = {
      pathname: sqlPaths.sqlEditor.link(),
      search: `?context="${encodeURIComponent(
        "@test_user",
      )}"&queryPath=${encodeURIComponent(secondFullPath)}`,
    };

    expect(dataset).have.length(2);

    expect(dataset.at(0).prop("to")).deep.equal(firstTo);
    expect(dataset.at(1).prop("to")).deep.equal(secondTo);
    expect(mainSettingsBtn.childAt(0).prop("dataset")).equal(
      commonProps.searchData.get(1),
    );
    expect(mainSettingsBtn.childAt(1).prop("fullPath")).deep.equal(
      commonProps.searchData.get(1).get("fullPath"),
    );
  });
});
