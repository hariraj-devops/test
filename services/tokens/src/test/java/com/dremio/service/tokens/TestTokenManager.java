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
package com.dremio.service.tokens;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.dac.proto.model.tokens.SessionState;
import com.dremio.datastore.adapter.LegacyKVStoreProviderAdapter;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.server.options.OptionValidatorListingImpl;
import com.dremio.exec.server.options.SystemOptionManager;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValue;
import com.dremio.options.impl.DefaultOptionManager;
import com.dremio.options.impl.OptionManagerWrapper;
import com.dremio.service.scheduler.SchedulerService;
import com.dremio.test.DremioTest;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Provider;
import org.glassfish.jersey.uri.UriComponent;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Test token management. */
public class TestTokenManager {

  private static TokenManagerImpl manager;
  private static LegacyKVStoreProvider provider;

  private static final String username = "testuser";
  private static final String clientAddress = "localhost";

  @BeforeClass
  public static void startServices() throws Exception {
    OptionManager optionManager =
        OptionManagerWrapper.Builder.newBuilder()
            .withOptionValidatorProvider(mock(OptionValidatorListingImpl.class))
            .withOptionManager(mock(DefaultOptionManager.class))
            .withOptionManager(mock(SystemOptionManager.class))
            .build();
    when(optionManager.getOption("token.expiration.min"))
        .thenReturn(
            OptionValue.createOption(
                OptionValue.Kind.LONG,
                OptionValue.OptionType.SYSTEM,
                "token.expiration.min",
                "30"));
    when(optionManager.getOption("token.release.leadership.ms"))
        .thenReturn(
            OptionValue.createOption(
                OptionValue.Kind.LONG,
                OptionValue.OptionType.SYSTEM,
                "token.release.leadership.ms",
                "144000000"));
    when(optionManager.getOption("token.temporary.expiration.sec"))
        .thenReturn(
            OptionValue.createOption(
                OptionValue.Kind.LONG,
                OptionValue.OptionType.SYSTEM,
                "token.temporary.expiration.sec",
                "5"));

    provider = LegacyKVStoreProviderAdapter.inMemory(DremioTest.CLASSPATH_SCAN_RESULT);
    provider.start();
    manager =
        new TokenManagerImpl(
            new Provider<LegacyKVStoreProvider>() {
              @Override
              public LegacyKVStoreProvider get() {
                return provider;
              }
            },
            new Provider<SchedulerService>() {
              @Override
              public SchedulerService get() {
                return mock(SchedulerService.class);
              }
            },
            new Provider<OptionManager>() {
              @Override
              public OptionManager get() {
                return optionManager;
              }
            },
            false,
            10,
            10);
    manager.start();
  }

  @AfterClass
  public static void stopServices() throws Exception {
    if (provider != null) {
      provider.close();
    }
    if (manager != null) {
      manager.close();
    }
  }

  @Test
  public void validToken() throws Exception {
    final TokenDetails details = manager.createToken(username, clientAddress);

    TokenDetails validateResult = manager.validateToken(details.token);
    assertEquals(validateResult.username, username);
    assertTrue(validateResult.getScopes().isEmpty());

    SessionState getResult = manager.getTokenStore().get(details.token);
    assertNotNull(getResult);
    assertNull(getResult.getScopesList());
  }

  @Test
  public void validTemporaryToken() throws Exception {
    URI request = new URI("/path?query=test").normalize();
    final TokenDetails details =
        manager.createTemporaryToken(
            username,
            request.getPath(),
            UriComponent.decodeQuery(request, true),
            TimeUnit.SECONDS.toMillis(30));
    final TokenDetails validateDetails =
        manager.validateTemporaryToken(
            details.token, request.getPath(), UriComponent.decodeQuery(request, true));
    assertEquals(validateDetails.username, username);
    assertTrue(validateDetails.getScopes().isEmpty());
    assertNotNull(manager.getTokenStore().get(validateDetails.token));
    final long actualDuration =
        validateDetails.expiresAt
            - manager.getTokenStore().get(validateDetails.token).getIssuedAt();
    assertEquals(actualDuration, TimeUnit.SECONDS.toMillis(5)); // sys option set to 5 sec
  }

  @Test
  public void validThirdPartyToken() {
    final TokenDetails details =
        manager.createThirdPartyToken(
            username,
            "",
            "some-client-id",
            Arrays.asList("dremio.all", "offline_access"),
            TimeUnit.HOURS.toMillis(1));
    TokenDetails validationResult = manager.validateToken(details.token);
    assertEquals(username, validationResult.username);
    assertTrue(
        validationResult.expiresAt > System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(59));
    assertEquals(Arrays.asList("dremio.all", "offline_access"), validationResult.getScopes());
    assertNotNull(manager.getTokenStore().get(details.token));
    assertTrue(details.expiresAt > System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(59));
    assertEquals(Arrays.asList("dremio.all", "offline_access"), details.getScopes());
  }

  @Test
  public void nullToken() throws Exception {
    try {
      manager.validateToken(null);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals(e.getMessage(), "invalid token");
    }
  }

  @Test
  public void invalidToken() throws Exception {
    try {
      manager.validateToken("hopethistokenisnevergenerated");
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals(e.getMessage(), "invalid token");
    }
  }

  @Test
  public void useTokenAfterExpiry() throws Exception {
    final long now = System.currentTimeMillis();
    final long expires = now + 1000;

    final TokenDetails details =
        manager.createToken(username, clientAddress, now, expires, null, null, null);
    assertEquals(manager.validateToken(details.token).username, username);
    assertTrue(manager.getTokenStore().get(details.token) != null);

    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      fail("I was sleeping!");
    }

    try {
      manager.validateToken(details.token);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals(e.getMessage(), "token expired");
    }

    assertTrue(manager.getTokenStore().get(details.token) == null);
  }

  @Test
  public void useTemporaryTokenAfterExpiry() throws Exception {
    URI request = new URI("/path?query=test").normalize();
    final TokenDetails details =
        manager.createTemporaryToken(
            username,
            request.getPath(),
            UriComponent.decodeQuery(request, true),
            TimeUnit.SECONDS.toMillis(2));
    assertEquals(
        manager.validateTemporaryToken(
                details.token, request.getPath(), UriComponent.decodeQuery(request, true))
            .username,
        username);
    assertNotNull(manager.getTokenStore().get(details.token));

    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      fail("I was sleeping!");
    }

    try {
      manager.validateTemporaryToken(
          details.token, request.getPath(), UriComponent.decodeQuery(request, true));
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals(e.getMessage(), "token expired");
    }

    assertNull(manager.getTokenStore().get(details.token));
  }

  @Test
  public void useThirdPartyTokenAfterExpiry() throws Exception {
    final TokenDetails details =
        manager.createThirdPartyToken(
            username,
            "",
            "some-client-id",
            Arrays.asList("dremio.all", "offline_access"),
            TimeUnit.SECONDS.toMillis(1));

    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      fail("I was sleeping!");
    }

    try {
      manager.validateToken(details.token);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals(e.getMessage(), "token expired");
    }

    assertNull(manager.getTokenStore().get(details.token));
  }

  @Test
  public void useTokenAfterLogOut() throws Exception {
    final long now = System.currentTimeMillis();
    final long expires = now + (10 * 1000);

    final TokenDetails details =
        manager.createToken(username, clientAddress, now, expires, null, null, null);
    assertEquals(manager.validateToken(details.token).username, username);
    assertTrue(manager.getTokenStore().get(details.token) != null);

    manager.invalidateToken(details.token); // ..logout

    try {
      manager.validateToken(details.token);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals(e.getMessage(), "invalid token");
    }

    assertTrue(manager.getTokenStore().get(details.token) == null);
  }

  @Test
  public void checkExpiration() throws Exception {
    final long now = System.currentTimeMillis();
    final long expires = now + (10 * 1000);

    final TokenDetails details =
        manager.createToken(username, clientAddress, now, expires, null, null, null);
    assertEquals((long) manager.getTokenStore().get(details.token).getExpiresAt(), expires);
  }

  @Test
  public void testCustomDurationAndScope() {
    final long now = System.currentTimeMillis();
    final long expires = now + (3 * 1000);

    final TokenDetails createResult =
        manager.createToken(username, null, expires, List.of("dremio.all"));
    assertFalse(createResult.token.isEmpty());
    assertEquals(username, createResult.username);
    assertEquals(List.of("dremio.all"), createResult.getScopes());

    final TokenDetails validateResult = manager.validateToken(createResult.token);
    assertEquals(List.of("dremio.all"), validateResult.getScopes());
    assertEquals(username, validateResult.username);
    assertEquals(expires, validateResult.expiresAt);
  }

  @Test
  public void createJwtThrows() {
    assertThrows(
        UnsupportedOperationException.class, () -> manager.createJwt(username, clientAddress));
    assertThrows(
        UnsupportedOperationException.class, () -> manager.createJwt(username, clientAddress, 0L));
  }
}
