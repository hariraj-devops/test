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
package com.dremio.datastore.transientstore;

import java.io.IOException;

public interface Marshaller<V> {
  Marshaller<byte[]> BYTE_MARSHALLER =
      new Marshaller<>() {
        @Override
        public byte[] marshal(byte[] bytes) {
          return bytes;
        }

        @Override
        public byte[] unmarshal(byte[] bytes) throws IOException {
          return bytes;
        }
      };

  byte[] marshal(V v);

  V unmarshal(byte[] bytes) throws IOException;

  default Marshaller<V> withCompression() {
    return new CompressedMarshaller<>(this);
  }
}
