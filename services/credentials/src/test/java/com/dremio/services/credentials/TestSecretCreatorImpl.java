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
package com.dremio.services.credentials;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Optional;
import org.junit.Test;

/** Test {@link SecretsCreatorImpl}. */
public class TestSecretCreatorImpl extends TestSecretsCreatorCommon {
  @Override
  protected SecretsCreator initSecretsCreator() {
    return new SecretsCreatorImpl(
        TestSecretsCreatorCommon::getSystemCipher, TestSecretsCreatorCommon::getCredentialService);
  }

  @Test
  public void testEncryptRejectSomeKnownScheme0() throws CredentialsException {
    final String rawSecret = "system:welcome!23";
    final Optional<URI> encryptedSecret = getSecretsCreator().encrypt(rawSecret);
    assertTrue(encryptedSecret.isEmpty());
  }

  @Test
  public void testEncryptRejectSomeKnownScheme1() throws CredentialsException {
    final String rawSecret = "secret:welcome!23";
    final Optional<URI> encryptedSecret = getSecretsCreator().encrypt(rawSecret);
    assertTrue(encryptedSecret.isEmpty());
  }

  @Test
  public void testEncryptRejectSomeKnownScheme2() throws CredentialsException {
    final String rawSecret = "dcsecret:welcome!23";
    // Since we use mock for credentialService, so not all the schemes are tested here. Basically
    // any scheme supported by Dremio (except data scheme URI) will be rejected.
    final Optional<URI> encryptedSecret = getSecretsCreator().encrypt(rawSecret);
    assertTrue(encryptedSecret.isEmpty());
  }
}
