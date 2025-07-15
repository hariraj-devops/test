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

import java.util.Arrays;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

public class CompressedMarshallerTest {

  @Test
  public void testMarshallingUncompressable() {
    CompressedMarshaller<byte[]> marshaller =
        new CompressedMarshaller<>(Marshaller.BYTE_MARSHALLER);
    byte[] bytes = new byte[10_000];
    Random random = new Random(5);
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) random.nextInt(255);
    }
    byte[] result = marshaller.marshal(bytes);
    byte[] unmarshalled = marshaller.unmarshal(result);
    Assert.assertEquals(10_001, result.length);
    Assert.assertTrue(Arrays.equals(bytes, unmarshalled));
  }

  @Test
  public void testMarshallingCompressable() {
    CompressedMarshaller<byte[]> marshaller =
        new CompressedMarshaller<>(Marshaller.BYTE_MARSHALLER);
    byte[] bytes = new byte[256 * 2_000];

    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) i;
    }
    byte[] result = marshaller.marshal(bytes);
    byte[] unmarshalled = marshaller.unmarshal(result);
    Assert.assertEquals(26_028, result.length);
    Assert.assertTrue(Arrays.equals(bytes, unmarshalled));
  }

  @Test
  public void testEmptyArray() {
    CompressedMarshaller<byte[]> marshaller =
        new CompressedMarshaller<>(Marshaller.BYTE_MARSHALLER);
    byte[] bytes = new byte[0];
    byte[] result = marshaller.marshal(bytes);
    byte[] unmarshalled = marshaller.unmarshal(result);
    Assert.assertEquals(1, result.length);
    Assert.assertTrue(Arrays.equals(bytes, unmarshalled));
  }
}
