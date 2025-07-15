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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.dremio.config.DremioConfig;
import com.dremio.test.DremioTest;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Test common behaviors of {@link SecretsCreatorImpl} and {@link MigrationSecretsCreator}. */
public abstract class TestSecretsCreatorCommon extends DremioTest {

  @ClassRule public static TemporaryFolder tempFolder = new TemporaryFolder();

  private static CredentialsService credentialService;

  private static Cipher systemCipher;
  private SecretsCreator secretsCreatorImpl;

  protected abstract SecretsCreator initSecretsCreator();

  protected static CredentialsService getCredentialService() {
    return credentialService;
  }

  protected static Cipher getSystemCipher() {
    return systemCipher;
  }

  protected SecretsCreator getSecretsCreator() {
    return secretsCreatorImpl;
  }

  @BeforeClass
  public static void init() throws IOException {
    final DremioConfig config =
        DEFAULT_DREMIO_CONFIG.withValue(
            DremioConfig.LOCAL_WRITE_PATH_STRING, tempFolder.newFolder().toString());
    systemCipher = new SystemCipher(config, null);

    credentialService = mock(CredentialsService.class);
    doAnswer(
            invocation -> {
              URI uri = invocation.getArgument(0);
              return CredentialsServiceUtils.isEncryptedCredentials(uri);
            })
        .when(credentialService)
        .isSupported(any(URI.class));
  }

  @Before
  public void setUp() throws IOException {
    secretsCreatorImpl = initSecretsCreator();
  }

  @Test
  public void testEncryptSkipEncryptedSecret() throws CredentialsException {
    final String rawSecret = "welcome!23";
    final Optional<URI> encryptedSecret = secretsCreatorImpl.encrypt(rawSecret);
    assertTrue(encryptedSecret.isPresent());

    final String encryptedSecretString = encryptedSecret.get().toString();
    final Optional<URI> attempt = secretsCreatorImpl.encrypt(encryptedSecretString);
    assertTrue(attempt.isEmpty());
  }

  @Test
  public void testEncryptDoesNotRejectDataScheme() throws CredentialsException {
    final String rawSecret = "system:welcome!23";
    final URI encodedByUI = CredentialsServiceUtils.encodeAsDataURI(rawSecret);
    final Optional<URI> encryptedSecret = secretsCreatorImpl.encrypt(encodedByUI.toString());
    assertTrue(encryptedSecret.isPresent());
  }

  @Test
  public void testIsEncrypted() throws CredentialsException {
    final String rawSecret = "welcome!23";
    final Optional<URI> encryptedSecret = secretsCreatorImpl.encrypt(rawSecret);
    assertTrue(encryptedSecret.isPresent());

    final String encryptedSecretString = encryptedSecret.get().toString();
    assertTrue(secretsCreatorImpl.isEncrypted(encryptedSecretString));
  }

  @Test
  public void testIsEncryptedForRawSecret() {
    final String rawSecret = "welcome!23";
    assertFalse(secretsCreatorImpl.isEncrypted(rawSecret));
  }

  @Test
  public void testIsEncryptedForEncryptedSecretWithoutScheme() throws CredentialsException {
    final String rawSecret = "welcome!23";
    final Optional<URI> encryptedSecret = secretsCreatorImpl.encrypt(rawSecret);
    assertTrue(encryptedSecret.isPresent());

    final URI encryptedSecretURI = encryptedSecret.get();
    assertFalse(secretsCreatorImpl.isEncrypted(encryptedSecretURI.getSchemeSpecificPart()));
  }

  @Test
  public void testEncryptFakeDataSchemeSecret() throws CredentialsException {
    final String rawSecret = "data:welcome!23"; // Not a true data scheme secret!
    final Optional<URI> encryptedSecret = secretsCreatorImpl.encrypt(rawSecret);
    assertTrue(encryptedSecret.isPresent());
  }
}
