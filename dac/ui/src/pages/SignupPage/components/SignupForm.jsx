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
import { PureComponent } from "react";
import Immutable from "immutable";

import PropTypes from "prop-types";
import { Button } from "dremio-ui-lib/components";
import ViewStateWrapper from "components/ViewStateWrapper";
import { createFirstUser } from "actions/admin";
import { noUsersError } from "actions/account";
import { getViewState } from "selectors/resources";
import {
  connectComplexForm,
  InnerComplexForm,
} from "components/Forms/connectComplexForm";
import { divider, formRow } from "uiTheme/radium/forms";
import localStorageUtils from "utils/storageUtils/localStorageUtils";

import UserForm from "components/Forms/UserForm";

import SignupTitle from "./SignupTitle";
import { intl } from "#oss/utils/intl";

export const SIGNUP_FORM_VIEW_ID = "SIGNUP_FORM_VIEW_ID";

export class SignupForm extends PureComponent {
  static propTypes = {
    createFirstUser: PropTypes.func,
    fields: PropTypes.object,
    viewState: PropTypes.instanceOf(Immutable.Map),
    noUsersError: PropTypes.func,
    location: PropTypes.object.isRequired,
  };

  state = {
    showSpinner: false,
  };

  componentDidMount() {
    this.props.noUsersError(); // if user navigated directly to /signup - ensure socket closing, etc
  }

  submit = (form) => {
    const instanceId = localStorageUtils.getInstanceId();
    const mappedValues = {
      userName: form.userName,
      firstName: form.firstName,
      lastName: form.lastName,
      email: form.email,
      createdAt: new Date().getTime(),
      password: form.password,
      extra: form.extra || instanceId,
    };
    const viewId = SIGNUP_FORM_VIEW_ID;
    this.setState({ showSpinner: true }, () => {
      return this.props
        .createFirstUser(mappedValues, { viewId })
        .finally(() => this.setState({ showSpinner: false }));
    });
  };

  render() {
    const { viewState, fields } = this.props;

    return (
      <div id="signup-form" style={styles.base}>
        <SignupTitle />
        <ViewStateWrapper viewState={viewState} />
        <InnerComplexForm
          {...this.props}
          style={styles.form}
          onSubmit={this.submit}
        >
          <UserForm fields={fields} style={{ padding: 0 }} />
          <hr style={{ ...divider, width: "100vw" }} />
          <div style={styles.footer}>
            <div style={styles.submit}>
              <Button
                type="submit"
                variant="primary"
                pending={this.state.showSpinner}
              >
                {intl.formatMessage({ id: "Common.Next" })}
              </Button>
            </div>
            <div style={styles.footerLink}>
              <a
                href="https://www.dremio.com/legal/privacy-policy"
                target="_blank"
                rel="noopener noreferrer"
              >
                {laDeprecated("Privacy")}
              </a>
            </div>
          </div>
        </InnerComplexForm>
      </div>
    );
  }
}

const styles = {
  base: {
    width: 640,
  },
  form: {
    display: "flex",
    flexWrap: "wrap",
  },
  formRow: {
    ...formRow,
    display: "flex",
  },
  formRowSingle: {
    ...formRow,
    display: "flex",
    width: "100%",
  },
  footer: {
    display: "flex",
    width: "100%",
    alignItems: "center",
    justifyContent: "space-between",
  },
  footerLink: {
    marginBottom: "5px",
  },
  submit: {
    display: "flex",
    alignItems: "center",
  },
};

function mapToFormState(state) {
  return {
    viewState: getViewState(state, SIGNUP_FORM_VIEW_ID),
  };
}

export default connectComplexForm(
  {
    form: "signup",
  },
  [UserForm],
  mapToFormState,
  { createFirstUser, noUsersError },
)(SignupForm);
