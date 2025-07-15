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
package com.dremio.dac.cmd;

import com.dremio.dac.model.usergroup.UserLogin;
import com.dremio.dac.model.usergroup.UserLoginSession;
import com.dremio.dac.server.BaseTestServer;
import com.dremio.dac.server.DACConfig;
import com.dremio.dac.server.GenericErrorMessage;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response.Status;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/** Test reset password */
public class TestResetPassword extends BaseTestServer {

  @BeforeClass
  public static void init() throws Exception {
    Assume.assumeFalse(BaseTestServer.isMultinode());

    enableDefaultUser(false);
    inMemoryStorage(false);
    addDefaultUser(true);

    BaseTestServer.init();
  }

  @AfterClass
  public static void shutdown() {
    enableDefaultUser(true);
    inMemoryStorage(true);
    addDefaultUser(false);
  }

  @Test
  public void testResetPassword() throws Exception {
    DACConfig dacConfig = getCurrentDremioDaemon().getDACConfig();
    BaseTestServer.close();
    SetPassword.resetPassword(dacConfig, DEFAULT_USERNAME, "tshiran123456");
    BaseTestServer.init();

    UserLogin userLogin = new UserLogin(DEFAULT_USERNAME, DEFAULT_PASSWORD);
    expectStatus(
        Status.UNAUTHORIZED,
        getHttpClient().getAPIv2().path("/login").request(JSON).buildPost(Entity.json(userLogin)),
        GenericErrorMessage.class);
    userLogin = new UserLogin(DEFAULT_USERNAME, "tshiran123456");
    expectSuccess(
        getHttpClient().getAPIv2().path("/login").request(JSON).buildPost(Entity.json(userLogin)),
        UserLoginSession.class);
  }
}
