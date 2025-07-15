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
package com.dremio.dac.server;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link DisableHttpMethodsFilter} */
public class TestDisableHttpMethodsFilter {
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final HttpServletResponse response = mock(HttpServletResponse.class);
  private final FilterChain filterChain = mock(FilterChain.class);

  @After
  public void afterEach() {
    reset(request);
    reset(response);
    reset(filterChain);
  }

  @Test
  public void testFilterAllowRequestedMethod() throws ServletException, IOException {
    // check method is not blocked if non-specified in the filter.
    DisableHttpMethodsFilter filter =
        new DisableHttpMethodsFilter(ImmutableSet.of("TRACE", "OPTIONS"));
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/foo/bar");
    filter.doFilter(request, response, filterChain);
    verify(filterChain).doFilter(request, response);
  }

  @Test
  public void testFilterBlockRequestedMethod() throws ServletException, IOException {
    // Check method is blocked if specified in the filter.
    DisableHttpMethodsFilter filter =
        new DisableHttpMethodsFilter(ImmutableSet.of("TRACE", "OPTIONS"));
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("TRACE");
    when(request.getRequestURI()).thenReturn("/foo/bar");
    filter.doFilter(request, response, filterChain);
    ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(response, times(1)).sendError(statusCaptor.capture());
    verify(filterChain, never()).doFilter(request, response);
  }
}
