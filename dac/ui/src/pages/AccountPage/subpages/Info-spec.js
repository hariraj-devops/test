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
import { minimalFormProps } from "testUtil";
import EditUserForm from "#oss/pages/AdminPage/components/forms/EditUserForm";
import { Info } from "./Info";

describe("Info", () => {
  let minimalProps;
  let commonProps;
  beforeEach(() => {
    minimalProps = {
      ...minimalFormProps(),
      onFormSubmit: sinon.spy(),
      handleSubmit: sinon.stub().returns(() => {}),
      cancel: sinon.spy(),
      fields: {},
      updateFormDirtyState: () => {},
    };
    commonProps = {
      ...minimalProps,
    };
  });

  it("should render with minimal props without exploding", () => {
    const wrapper = shallow(<Info {...minimalProps} />);
    expect(wrapper).to.have.length(1);
  });

  it("should render with common props without exploding", () => {
    const wrapper = shallow(<Info {...commonProps} />);
    expect(wrapper).to.have.length(1);
  });

  it("should render FormInfo with common props", () => {
    const wrapper = shallow(<Info {...commonProps} />);
    expect(wrapper.find(EditUserForm)).to.have.length(1);
  });

  it("should render UserForm with common props", () => {
    const wrapper = shallow(<Info {...commonProps} />);
    expect(wrapper.find(EditUserForm)).to.have.length(1);
  });
});
