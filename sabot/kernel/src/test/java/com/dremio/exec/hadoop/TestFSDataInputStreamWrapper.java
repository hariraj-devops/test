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
package com.dremio.exec.hadoop;

import static com.dremio.exec.hadoop.FSErrorTestUtils.getDummyArguments;
import static com.dremio.exec.hadoop.FSErrorTestUtils.newFSError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.dremio.io.FSInputStream;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Stream;
import org.apache.hadoop.fs.ByteBufferReadable;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.fs.PositionedReadable;
import org.apache.hadoop.fs.Seekable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Test to verify how {@code FSDataInputStream} handle {@code FSError} */
public class TestFSDataInputStreamWrapper {
  private static Stream<Method> test() {
    return FluentIterable.from(FSInputStream.class.getMethods())
        .filter(
            new Predicate<Method>() {
              @Override
              public boolean apply(Method input) {
                if (Modifier.isStatic(input.getModifiers())) {
                  return false;
                }
                if (!Modifier.isPublic(input.getModifiers())) {
                  return false;
                }
                return Arrays.asList(input.getExceptionTypes()).contains(IOException.class);
              }
            })
        .stream();
  }

  private static Class<?> getClass(String clsName) {
    try {
      return Class.forName(clsName);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  @ParameterizedTest(name = "method: {0}")
  @MethodSource
  public void test(Method method) throws Exception {
    Class<?> byteBufferPositionedReadableClass =
        getClass("org.apache.hadoop.fs.ByteBufferPositionedReadable");

    final IOException ioException = new IOException("test io exception");
    final FSError fsError = newFSError(ioException);
    FSDataInputStream fdis =
        new FSDataInputStream(
            mock(
                InputStream.class,
                withSettings()
                    .extraInterfaces(
                        Seekable.class,
                        byteBufferPositionedReadableClass == null
                            ? AutoCloseable.class
                            : byteBufferPositionedReadableClass,
                        PositionedReadable.class,
                        ByteBufferReadable.class)
                    .defaultAnswer(
                        new Answer<Object>() {
                          @Override
                          public Object answer(InvocationOnMock invocation) throws Throwable {
                            throw fsError;
                          }
                        })));

    FSInputStream fdisw = FSDataInputStreamWrapper.of(fdis);
    Object[] params = getDummyArguments(method);
    try {
      method.invoke(fdisw, params);
    } catch (InvocationTargetException e) {
      if (byteBufferPositionedReadableClass == null) {
        assertThat(e.getTargetException())
            .isInstanceOfAny(IOException.class, UnsupportedOperationException.class);
      } else {
        assertThat(e.getTargetException()).isInstanceOf(IOException.class);
      }
      if (e.getTargetException() instanceof IOException) {
        assertThat((IOException) e.getTargetException()).isSameAs(ioException);
      }
    }
  }
}
