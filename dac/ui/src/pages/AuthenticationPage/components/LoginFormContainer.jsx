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
import { compose } from "redux";
import { Link, withRouter } from "react-router";

import LoginFormMixin from "@inject/pages/AuthenticationPage/components/LoginFormMixin";
import localStorageUtils from "dyn-load/utils/storageUtils/localStorageUtils";
import { renderSSOLoginToggleLink } from "dyn-load/utils/loginUtils";
import LoginForm from "./LoginForm";
import { BetaTag } from "./BetaTag/BetaTag";

const isBeta = process.env.DREMIO_BETA === "true";

export class LoginFormContainer extends PureComponent {
  constructor(props) {
    super(props);
    this.state = {
      loginScreen: localStorageUtils.renderSSOLoginScreen(),
    };
  }

  componentDidMount() {
    if (this.state.loginScreen === null) {
      this.setLoginScreen();
    }
  }

  renderForm() {
    return <LoginForm {...this.props} />;
  }

  setLoginScreen() {
    localStorageUtils.setSSOLoginChoice();
    this.setState({
      loginScreen: localStorageUtils.renderSSOLoginScreen(),
    });
  }

  render() {
    return (
      <div className="login-form-wrapper">
        <div id="login-form" className="drop-shadow-lg" style={styles.base}>
          <h1 className="flex justify-center items-center dremio-typography-large dremio-typography-bold mb-3">
            Log in
            {isBeta && <BetaTag />}
          </h1>
          {this.renderForm({
            loginType: this.state.loginScreen,
            ssoPending: !!this.props.ssoPending,
          })}
          {renderSSOLoginToggleLink({
            setScreenFn: this.setLoginScreen.bind(this),
            ssoPending: !!this.props.ssoPending,
          })}
        </div>
        <div className="flex justify-center mt-5 dremio-typography-small">
          <Link
            to="https://www.dremio.com/legal/privacy-policy"
            target="_blank"
            rel="noopener noreferrer"
          >
            {laDeprecated("Privacy policy")}
          </Link>
        </div>
      </div>
    );
  }
}
export default compose(withRouter, LoginFormMixin)(LoginFormContainer);

const styles = {
  base: {
    position: "relative",
    backgroundColor: "var(--fill--login--foreground)",
    width: 400,
    overflow: "hidden",
    padding: "var(--dremio--spacing--3)",
    display: "flex",
    borderRadius: "4px",
    flexDirection: "column",
  },
};
