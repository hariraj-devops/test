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
import { browserHistory } from "react-router";
import { shallow } from "enzyme";
import Immutable from "immutable";
import { MainInfoItemName } from "./MainInfoItemName";

describe("MainInfoItemName", () => {
  const commonProps = {
    item: Immutable.fromJS({
      id: 1,
      name: "foo",
      links: { self: "/href", query: "/query" },
      queryable: false,
      fileType: "folder",
      fullPathList: ["Prod-sample"],
      datasetConfig: { fullPathList: [] },
    }),
  };

  const queryableFolderProps = {
    item: Immutable.fromJS({
      id: 1,
      name: "foo",
      links: { self: "/href", query: "/query" },
      queryable: true,
      fileType: "folder",
      fullPathList: ["Prod-sample"],
      datasetConfig: { fullPathList: [] },
    }),
  };

  const fileProps = {
    item: Immutable.fromJS({
      id: 2,
      name: "fooFile",
      links: { self: "/href" },
      fileType: "file",
      fileFormat: { fullPath: ["Prod-sample"] },
      entityType: "file",
      query: { then: "query" },
      filePath: "Prod-sample/prod",
      fullPathList: ["Prod-sample"],
      datasetConfig: { fullPathList: [] },
    }),
  };

  it("renders <div>", () => {
    const wrapper = shallow(<MainInfoItemName {...commonProps} />);
    expect(wrapper.type()).to.eql("div");
  });

  it("should render Link to href", () => {
    const wrapper = shallow(<MainInfoItemName {...commonProps} />);
    expect(wrapper.find("Link").props().to).to.eql("/href");
  });

  it("should render color only for all clickable items", () => {
    const wrapper1 = shallow(<MainInfoItemName {...queryableFolderProps} />);
    const wrapper2 = shallow(<MainInfoItemName {...commonProps} />);
    expect(wrapper1.find("Link").props().style.color).to.eql(undefined);
    expect(wrapper2.find("Link").props().style.color).to.eql(undefined);
  });

  it("should render modal href for non-queryable file", () => {
    const location = browserHistory.getCurrentLocation();
    const wrapper = shallow(<MainInfoItemName {...fileProps} />);
    expect(wrapper.find("Link").props().to).to.eql({
      ...location,
      state: {
        modal: "DatasetSettingsModal",
        tab: "format",
        entityName: "Prod-sample",
        entityType: "file",
        type: "file",
        entityId: 2,
        fullPath: "Prod-sample/prod",
        query: { then: "query" },
        isHomePage: true,
      },
    });
  });

  it("should render name as text", () => {
    const wrapper = shallow(<MainInfoItemName {...commonProps} />);
    expect(wrapper.find("EllipsedText").props().text).to.eql("foo");
  });

  it("should render dremio-icon type depending on fileType", () => {
    const wrapper = shallow(
      <MainInfoItemName item={commonProps.item.set("fileType", "dataset")} />,
    );
    expect(wrapper.find("dremio-icon").props().name).to.eql(
      "entities/dataset-view",
    );
  });
});
