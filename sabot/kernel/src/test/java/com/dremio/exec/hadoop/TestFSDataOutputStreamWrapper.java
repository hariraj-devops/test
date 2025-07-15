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

import com.dremio.io.FSOutputStream;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Stream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSError;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Test to verify how {@code FSDataOutputStream} handle {@code FSError} */
public class TestFSDataOutputStreamWrapper {

  private static Stream<Method> test() {
    return FluentIterable.from(FSOutputStream.class.getMethods())
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

  @ParameterizedTest(name = "method: {0}")
  @MethodSource
  public void test(Method method) throws Exception {
    final IOException ioException = new IOException("test io exception");
    final FSError fsError = newFSError(ioException);
    FSDataOutputStream fdos =
        mock(
            FSDataOutputStream.class,
            new Answer<Object>() {
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                throw fsError;
              }
            });

    FSDataOutputStreamWrapper fdosw = new FSDataOutputStreamWrapper(fdos);
    Object[] params = getDummyArguments(method);
    try {
      method.invoke(fdosw, params);
    } catch (InvocationTargetException e) {
      assertThat(e.getTargetException()).isInstanceOf(IOException.class);
      assertThat((IOException) e.getTargetException()).isSameAs(ioException);
    }
  }
}
