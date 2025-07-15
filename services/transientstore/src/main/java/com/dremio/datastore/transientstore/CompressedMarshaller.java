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

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.Arrays;
import org.xerial.snappy.Snappy;

/**
 * A marshaller that compresses the data before storing it. The first byte of the byte array is used
 * to store the compression type to allow future exploration of different compression algorithms.
 */
class CompressedMarshaller<VALUE> implements Marshaller<VALUE> {
  public static final int UNCOMPRESSED = 0;
  public static final int SNAPPY = 1;

  private final Marshaller<VALUE> delegate;

  public CompressedMarshaller(Marshaller<VALUE> delegate) {
    this.delegate = delegate;
  }

  @Override
  public byte[] marshal(VALUE value) {
    try {
      final byte[] bytes = delegate.marshal(value);
      final byte[] compressed = new byte[Snappy.maxCompressedLength(bytes.length) + 1];
      int compressedSize = Snappy.compress(bytes, 0, bytes.length, compressed, 1);
      if (compressedSize >= bytes.length) {
        compressed[0] = UNCOMPRESSED;
        System.arraycopy(bytes, 0, compressed, 1, bytes.length);
        return Arrays.copyOf(compressed, bytes.length + 1);
      } else {
        compressed[0] = SNAPPY;
        return Arrays.copyOf(compressed, compressedSize + 1);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public VALUE unmarshal(byte[] bytes) {
    try {
      Preconditions.checkArgument(bytes.length > 0, "Empty byte array");
      switch (bytes[0]) {
        case UNCOMPRESSED:
          return delegate.unmarshal(Arrays.copyOfRange(bytes, 1, bytes.length));
        case SNAPPY:
          {
            int compressedLength = bytes.length - 1;
            byte[] uncompressed = new byte[Snappy.uncompressedLength(bytes, 1, compressedLength)];
            Snappy.uncompress(bytes, 1, compressedLength, uncompressed, 0);
            return delegate.unmarshal(uncompressed);
          }
        default:
          throw new IllegalArgumentException("Unknown compression type: " + bytes[0]);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Marshaller<VALUE> withCompression() {
    return this;
  }
}
