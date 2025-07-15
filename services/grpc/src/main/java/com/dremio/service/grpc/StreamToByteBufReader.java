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
package com.dremio.service.grpc;

import com.dremio.common.SuppressForbidden;
import com.google.common.base.Throwables;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.arrow.memory.ArrowBuf;

/**
 * Enable access to ReadableBuffer directly to copy data from a GRPC InputStream into arrow buffers.
 *
 * <p>Adapted from
 * https://github.com/apache/arrow/blob/53859262ea988f31ce33a469305251064b5a53b8/java/flight/flight-core/src/main/java/org/apache/arrow/flight/grpc/GetReadableBuffer.java
 */
@SuppressForbidden
public class StreamToByteBufReader {
  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(StreamToByteBufReader.class);
  private static AtomicInteger NON_OPTIMAL_READ = new AtomicInteger(0);
  private static final Field READABLE_BUFFER;
  private static final Class<?> BUFFER_INPUT_STREAM;

  private static final Method READ_BYTES_METHOD;

  static {
    Field readableBufferField = null;
    Class<?> bufferInputStreamClass = null;
    Method readBytesMethod = null;
    try {
      Class<?> clazz = Class.forName("io.grpc.internal.ReadableBuffers$BufferInputStream");

      Field bufferField = clazz.getDeclaredField("buffer");
      bufferField.setAccessible(true);

      Class<?> readableBufferClazz = Class.forName("io.grpc.internal.ReadableBuffer");
      readBytesMethod = readableBufferClazz.getDeclaredMethod("readBytes", ByteBuffer.class);

      // don't set until we've gotten past all exception cases.
      readableBufferField = bufferField;
      bufferInputStreamClass = clazz;
    } catch (Exception e) {
      LOGGER.warn("Unable to setup optimal read path.", e);
    }
    READABLE_BUFFER = readableBufferField;
    BUFFER_INPUT_STREAM = bufferInputStreamClass;
    READ_BYTES_METHOD = readBytesMethod;
  }

  /**
   * Extracts the ReadableBuffer for the given input stream.
   *
   * @param is Must be an instance of io.grpc.internal.ReadableBuffers$BufferInputStream or null
   *     will be returned.
   */
  private static Object getReadableBuffer(InputStream is) {

    if (BUFFER_INPUT_STREAM == null
        || READ_BYTES_METHOD == null
        || !is.getClass().equals(BUFFER_INPUT_STREAM)) {
      return null;
    }

    try {
      return READABLE_BUFFER.get(is);
    } catch (Exception ex) {
      throw Throwables.propagate(ex);
    }
  }

  /**
   * Helper method to read a gRPC-provided InputStream into an Direct Buffer.
   *
   * @param stream The stream to read from. Should be an instance of {@link #BUFFER_INPUT_STREAM}.
   * @param buf The buffer to read into.
   * @param size The number of bytes to read.
   * @throws IOException if there is an error reading form the stream
   */
  public static void readIntoBuffer(final InputStream stream, final ArrowBuf buf, final int size)
      throws IOException {

    Object readableBuffer = getReadableBuffer(stream);
    if (readableBuffer != null) {
      try {
        READ_BYTES_METHOD.invoke(readableBuffer, buf.nioBuffer(0, size));
      } catch (IllegalAccessException | InvocationTargetException e) {
        readableBuffer = null;
      }
    }
    if (readableBuffer == null) {
      LOGGER.debug(
          "Entered non optimal read path {} number of times", NON_OPTIMAL_READ.incrementAndGet());
      byte[] heapBytes = new byte[size];
      ByteStreams.readFully(stream, heapBytes);
      buf.writeBytes(heapBytes);
    }
    buf.writerIndex(size);
  }
}
