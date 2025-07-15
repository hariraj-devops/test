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
package com.dremio.exec.catalog.conf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.SuppressForbidden;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.services.credentials.CredentialsService;
import com.dremio.services.credentials.CredentialsServiceUtils;
import com.dremio.services.credentials.MigrationSecretsCreator;
import com.dremio.services.credentials.SecretsCreator;
import com.dremio.test.DremioTest;
import io.protostuff.ByteString;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtobufIOUtil;
import io.protostuff.ProtostuffOutput;
import io.protostuff.Schema;
import io.protostuff.Tag;
import io.protostuff.runtime.DefaultIdStrategy;
import io.protostuff.runtime.Delegate;
import java.net.URI;
import java.util.Optional;
import java.util.function.Predicate;
import javax.inject.Provider;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests SecretRef and SecretRef ser/de via SecretRefDelegate. This test case uses its own code path
 * for ser/de to ensure the proper Protostuff paths are being utilized; these paths should mirror
 * their production counter-parts.
 */
@SuppressForbidden // Testing Unsafe SecretRef
public class TestSecretRef extends DremioTest {

  private DefaultIdStrategy idStrategy;
  private CredentialsService credentialsService;
  private SecretsCreator secretsCreator;
  private static final String TEST_SOURCE_TYPE = "test-secret-ref-source";
  private static final Predicate<String> IS_NOT_A_URI_FILTER =
      secret -> {
        String scheme;
        try {
          final URI uri = CredentialsServiceUtils.safeURICreate(secret);
          scheme = uri.getScheme();
        } catch (IllegalArgumentException ignored) {
          scheme = null;
        }

        return scheme == null;
      };

  @SourceType(value = TEST_SOURCE_TYPE, configurable = false)
  private static final class TestConnectionConf
      extends ConnectionConf<TestConnectionConf, StoragePlugin> {
    @Tag(1)
    @Secret
    public SecretRef secretRef;

    @Override
    public StoragePlugin newPlugin(
        PluginSabotContext pluginSabotContext,
        String name,
        Provider<StoragePluginId> pluginIdProvider) {
      return null;
    }
  }

  private static final String TEST_UPGRADE_SOURCE_TYPE = "test-secret-ref-upgrade-source";

  @SourceType(value = TEST_UPGRADE_SOURCE_TYPE, configurable = false)
  private static final class TestUpgradeConnectionConf
      extends ConnectionConf<TestConnectionConf, StoragePlugin> {
    @Tag(1)
    @Secret
    public String secret;

    @Override
    public StoragePlugin newPlugin(
        PluginSabotContext pluginSabotContext,
        String name,
        Provider<StoragePluginId> pluginIdProvider) {
      return null;
    }
  }

  private static final String TEST_UNSAFE_SOURCE_TYPE = "test-unsafe-secret-ref-source";

  @SuppressForbidden // Testing Unsafe SecretRef
  @SourceType(value = TEST_UNSAFE_SOURCE_TYPE, configurable = false)
  private static final class TestUnsafeConnectionConf
      extends ConnectionConf<TestConnectionConf, StoragePlugin> {
    @Tag(1)
    @Secret
    public SecretRefUnsafe secret;

    @Override
    public StoragePlugin newPlugin(
        PluginSabotContext pluginSabotContext,
        String name,
        Provider<StoragePluginId> pluginIdProvider) {
      return null;
    }
  }

  /** Explicitly register delegates before each test in case environment alters or changes */
  @Before
  public void before() {
    idStrategy = new DefaultIdStrategy();
    SecretRefImplDelegate.register(idStrategy);
    SecretRefUnsafeDelegate.register(idStrategy);
    assertTrue(idStrategy.isDelegateRegistered(SecretRef.class));
    assertTrue(idStrategy.isDelegateRegistered(SecretRefUnsafe.class));

    credentialsService = mock(CredentialsService.class);
    secretsCreator = mock(SecretsCreator.class);
  }

  @Test
  public void testEncryptAndLookupBasic() throws Exception {
    when(secretsCreator.encrypt(any())).thenReturn(Optional.of(new URI("system:encryptedSecret")));

    final SecretRefImpl secretRef = new SecretRefImpl("someSecretValue");
    assertTrue(secretRef.encrypt(secretsCreator));
    assertEquals("system:encryptedSecret", secretRef.getRaw());

    final CredentialsService credentialsService = mock(CredentialsService.class);
    when(credentialsService.lookup(any())).thenReturn("resolvedSecret");

    secretRef.decorateSecrets(credentialsService);
    assertEquals("resolvedSecret", secretRef.get());
    verify(credentialsService, atLeastOnce()).lookup(any());
  }

  @Test
  public void testAlreadyEncryptedOrUri() throws Exception {
    final SecretRefImpl encryptedSecretRef = new SecretRefImpl("system:alreadyEncrypted");
    final SecretRefImpl uriSecretRef = new SecretRefImpl("file:alreadyUri");
    assertFalse(encryptedSecretRef.encrypt(secretsCreator));
    assertFalse(uriSecretRef.encrypt(secretsCreator));
  }

  @Test
  public void testDoNotEncryptSystemEncryptedSecretAgain() throws Exception {
    when(secretsCreator.encrypt(any())).thenReturn(Optional.of(new URI("system:encryptedSecret")));
    when(secretsCreator.encrypt(eq("system:encryptedSecret"))).thenReturn(Optional.empty());
    when(secretsCreator.isEncrypted(anyString())).thenReturn(false);
    when(secretsCreator.isEncrypted(eq("system:encryptedSecret"))).thenReturn(true);

    final SecretRefImpl systemEncryptedSecretRef = new SecretRefImpl("system:encryptedSecret");
    final SecretRefImpl plainTextSecretRef = new SecretRefImpl("system:@123");
    final SecretRefImpl fileURISecretRef = new SecretRefImpl("file:alreadyUri");

    assertFalse(systemEncryptedSecretRef.encrypt(new MigrationSecretsCreator(secretsCreator)));
    assertTrue(plainTextSecretRef.encrypt(new MigrationSecretsCreator(secretsCreator)));
    assertTrue(fileURISecretRef.encrypt(new MigrationSecretsCreator(secretsCreator)));
  }

  /**
   * Simulate an upgrade scenario by serializing a conf with a string secret, then deserializing as
   * a different conf with SecretRef secret.
   */
  @Test
  public void testUpgrade() {
    final TestUpgradeConnectionConf oldConf = new TestUpgradeConnectionConf();
    oldConf.secret = "someSecretValue";
    final Schema<TestUpgradeConnectionConf> oldSchema = getSchema(TestUpgradeConnectionConf.class);
    final ByteString bytes = this.toBytes(oldConf, oldSchema);

    final Schema<TestConnectionConf> schema = getSchema(TestConnectionConf.class);
    final TestConnectionConf conf = schema.newMessage();
    ProtobufIOUtil.mergeFrom(bytes.toByteArray(), conf, schema);

    assertEquals(oldConf.secret, ((SecretRefImpl) conf.secretRef).getRaw());
  }

  @Test
  public void testSecretRefUnsafeSerDe() {
    final TestUnsafeConnectionConf conf = new TestUnsafeConnectionConf();
    conf.secret = new SecretRefUnsafe("someSecretValue");
    assertThrows(
        UnsupportedOperationException.class,
        () -> this.toBytes(conf, getSchema(TestUnsafeConnectionConf.class)));
  }

  @Test
  public void testSecretRefImplSerDe() {
    final TestConnectionConf conf = new TestConnectionConf();
    conf.secretRef = new SecretRefImpl("someSecretValue");
    final Schema<TestConnectionConf> schema = getSchema(TestConnectionConf.class);
    final ByteString bytes = this.toBytes(conf, schema);

    final TestConnectionConf newConf = schema.newMessage();
    ProtobufIOUtil.mergeFrom(bytes.toByteArray(), newConf, schema);
    assertEquals(conf.secretRef, newConf.secretRef);
  }

  @Test
  public void testNullSecretRef() {
    final TestConnectionConf conf = new TestConnectionConf();
    conf.secretRef = SecretRef.empty();
    final Schema<TestConnectionConf> schema = getSchema(TestConnectionConf.class);
    final ByteString bytes = this.toBytes(conf, schema);

    final TestConnectionConf newConf = schema.newMessage();
    ProtobufIOUtil.mergeFrom(bytes.toByteArray(), newConf, schema);
    assertEquals(newConf.secretRef, SecretRef.empty());
  }

  @Test
  public void testEmptyAndEquals() {
    assertEquals(new SecretRefImpl(""), SecretRef.empty());
    assertEquals(new SecretRefImpl("1234"), new SecretRefImpl("1234"));
  }

  @Test
  public void testEmptySecretRef() {
    final TestConnectionConf conf = new TestConnectionConf();
    final Schema<TestConnectionConf> schema = getSchema(TestConnectionConf.class);
    final ByteString bytes = this.toBytes(conf, schema);

    final TestConnectionConf newConf = schema.newMessage();
    ProtobufIOUtil.mergeFrom(bytes.toByteArray(), newConf, schema);
    assertNull(conf.secretRef);
  }

  @Test
  public void testEmptyEquals() {
    assertEquals(SecretRef.EMPTY, (SecretRef) () -> "");
    assertEquals(SecretRef.EMPTY, new SecretRefImpl(""));
    assertEquals(new SecretRefImpl(""), SecretRef.EMPTY);
    assertEquals(SecretRef.EMPTY, SecretRef.of(""));
    assertEquals(SecretRef.of(""), SecretRef.EMPTY);
    assertNotEquals(null, SecretRef.EMPTY);
    assertNotEquals(SecretRef.EMPTY, null);
  }

  @Test
  public void testSecretRefImplDelegateType() throws Exception {
    final Delegate<SecretRef> delegate = new SecretRefImplDelegate();
    assertThrows(
        IllegalArgumentException.class,
        () -> testDelegate(delegate, (SecretRef) () -> "lambdaValue"));
    assertThrows(
        IllegalArgumentException.class,
        () -> testDelegate(delegate, new SecretRefUnsafe("unsafe")));
    testDelegate(delegate, SecretRef.empty());
    testDelegate(delegate, SecretRef.of("regularSecretRef"));
  }

  @Test
  public void testExistingValue() {
    assertEquals(ConnectionConf.USE_EXISTING_SECRET_VALUE, SecretRef.EXISTING_VALUE.get());
    assertEquals(
        SecretRef.EXISTING_VALUE, new SecretRefImpl(ConnectionConf.USE_EXISTING_SECRET_VALUE));
    assertEquals(
        new SecretRefImpl(ConnectionConf.USE_EXISTING_SECRET_VALUE), SecretRef.EXISTING_VALUE);
    assertEquals(
        SecretRef.EXISTING_VALUE, (SecretRef) () -> ConnectionConf.USE_EXISTING_SECRET_VALUE);
    assertNotEquals(SecretRef.EXISTING_VALUE, null);
  }

  @Test
  public void testDisplayStringBasic() {
    assertEquals(SecretRef.EMPTY.get(), SecretRef.getDisplayString(SecretRef.EMPTY));
    assertEquals(
        SecretRef.EXISTING_VALUE.get(), SecretRef.getDisplayString(SecretRef.EXISTING_VALUE));
  }

  @Test
  public void testDisplayStringPlaintext() {
    final String somePlaintextSecret = "plaintextSecret";
    assertEquals(
        SecretRef.EXISTING_VALUE.get(),
        SecretRef.getDisplayString(SecretRef.of(somePlaintextSecret)));
    final String invalidUriSecret = "invalid%.plain/:text//";
    assertEquals(
        SecretRef.EXISTING_VALUE.get(), SecretRef.getDisplayString(SecretRef.of(invalidUriSecret)));
  }

  @Test
  public void testDisplayStringNotSupportedURI() throws Exception {
    final CredentialsService credentialsService = mock(CredentialsService.class);
    when(credentialsService.isSupported(any())).thenReturn(false);
    final SecretRef secretRef =
        new SecretRefImpl("unsupported:uri").decorateSecrets(credentialsService);
    assertEquals(SecretRef.EXISTING_VALUE.get(), SecretRef.getDisplayString(secretRef));
  }

  @Test
  public void testDisplayStringEncryptedSecret() {
    final CredentialsService credentialsService = mock(CredentialsService.class);
    when(credentialsService.isSupported(any())).thenReturn(true);
    final String encryptedSecret = "system:encrypted";
    final SecretRef secretRef =
        new SecretRefImpl(encryptedSecret).decorateSecrets(credentialsService);
    assertEquals(SecretRef.EXISTING_VALUE.get(), SecretRef.getDisplayString(secretRef));
  }

  @Test
  public void testDisplayStringSupportedURI() throws Exception {
    final CredentialsService credentialsService = mock(CredentialsService.class);
    when(credentialsService.isSupported(any())).thenReturn(true);
    final String supportedURI = "supported:uri";
    final SecretRef secretRef = new SecretRefImpl(supportedURI).decorateSecrets(credentialsService);
    assertEquals(supportedURI, SecretRef.getDisplayString(secretRef));
  }

  @Test
  public void testGetUriBasic() {
    assertNull(SecretRef.getURI(SecretRef.EMPTY));
    assertNull(SecretRef.getURI(SecretRef.EXISTING_VALUE));
  }

  @Test
  public void testGetUriPlaintext() {
    final String somePlaintextSecret = "plaintextSecret";
    assertNull(SecretRef.getURI(SecretRef.of(somePlaintextSecret)));
    final String invalidUriSecret = "invalid%.plain/:text//";
    assertNull(SecretRef.getURI(SecretRef.of(invalidUriSecret)));
  }

  @Test
  public void testGetUriSupported() {
    final String supportedURI = "supported:uri";
    final CredentialsService credentialsService = mock(CredentialsService.class);
    when(credentialsService.isSupported(any())).thenReturn(true);
    final SecretRef secretRef = new SecretRefImpl(supportedURI).decorateSecrets(credentialsService);
    assertEquals(supportedURI, SecretRef.getURI(secretRef).toString());
  }

  @Test
  public void testGetUriNotSupported() {
    final String notSupportedURI = "notsupported:uri";
    final CredentialsService credentialsService = mock(CredentialsService.class);
    when(credentialsService.isSupported(any())).thenReturn(false);
    final SecretRef secretRef =
        new SecretRefImpl(notSupportedURI).decorateSecrets(credentialsService);
    assertNull(SecretRef.getURI(secretRef));
  }

  @Test
  public void testGetUriEncryptedSecret() {
    final String encryptedSecret = "system:encrypted";
    final CredentialsService credentialsService = mock(CredentialsService.class);
    when(credentialsService.isSupported(any())).thenReturn(true);
    final SecretRef secretRef =
        new SecretRefImpl(encryptedSecret).decorateSecrets(credentialsService);
    assertEquals(encryptedSecret, SecretRef.getURI(secretRef).toString());
  }

  private <T extends ConnectionConf<?, ?>> Schema<T> getSchema(Class<T> clazz) {
    return ConnectionSchema.getSchema(clazz, idStrategy);
  }

  private <T extends ConnectionConf<?, ?>> ByteString toBytes(T conf, Schema<T> schema) {
    return ByteString.copyFrom(ProtobufIOUtil.toByteArray(conf, schema, LinkedBuffer.allocate()));
  }

  private void testDelegate(Delegate<SecretRef> delegate, SecretRef value) throws Exception {
    final ProtostuffOutput output = new ProtostuffOutput(LinkedBuffer.allocate());
    delegate.writeTo(output, 1, value, false);
  }
}
