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
package com.dremio.dac.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import com.dremio.dac.server.BaseTestServer;
import com.dremio.dac.server.test.SampleDataPopulator;
import com.dremio.service.tokens.TokenDetails;
import com.dremio.service.tokens.TokenManager;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class ITTokenManagerMultiCoordinator extends BaseTestServer {
  private TokenManager masterTokenManager;
  private TokenManager secondaryCoordTokenManager;

  @BeforeClass
  public static void init() throws Exception {
    // ignore the tests if not multinode.
    assumeTrue(isMultinode());

    enableDefaultUser(false);

    BaseTestServer.init();
    SampleDataPopulator.addDefaultFirstUser(getUserService(), getNamespaceService());
  }

  @Before
  public void before() {
    this.masterTokenManager = lMaster(TokenManager.class);
    this.secondaryCoordTokenManager = l(TokenManager.class);

    assertNotSame(masterTokenManager, secondaryCoordTokenManager);
  }

  @Test
  public void validateJwtOnSecondaryCoordinator() {
    final TokenDetails token = masterTokenManager.createJwt(DEFAULT_USERNAME, "localhost");
    final TokenDetails validatedToken = secondaryCoordTokenManager.validateToken(token.token);
    assertEquals(DEFAULT_USERNAME, validatedToken.username);
  }

  @Test
  public void secondaryCoordinatorsCannotSignJWTs() {
    assertThrows(
        "Secondary coordinators cannot sign JWTs",
        UnsupportedOperationException.class,
        () -> secondaryCoordTokenManager.createJwt(DEFAULT_USERNAME, "localhost"));
  }
}
