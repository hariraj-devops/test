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

import java.net.URI;
import java.util.Optional;

/** Primary interface for in-line encryption of source secrets. */
public interface SecretsCreator {

  /**
   * Validate and return true if the secret has already been encrypted by the system.
   *
   * @param secret string to check, can be a raw password OR a secret URI including its scheme
   */
  boolean isEncrypted(String secret);

  /**
   * Encrypt the given secret in-line, returning the encrypted value as a CredentialsService URI.
   * Rejects secrets that match the pattern of a {@link CredentialsProvider} URI. Always encrypts
   * secrets encoded as data credentials; callers should encode plaintext secrets where possible to
   * ensure secrets are encrypted.
   *
   * @param secret a raw password OR a secret URI including its scheme
   * @throws CredentialsException if error occurs during encryption.
   * @return URI representation of encrypted secret, empty if encryption was refused.
   */
  Optional<URI> encrypt(String secret) throws CredentialsException;

  /**
   * Clean up the secret and any associated side effects or metadata associated with its creation.
   *
   * @param secret URI of the created secret.
   * @return True, if anything was cleaned up.
   */
  default boolean cleanup(URI secret) {
    return false;
  }
}
