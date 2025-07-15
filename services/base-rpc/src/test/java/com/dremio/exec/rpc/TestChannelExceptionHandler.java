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
package com.dremio.exec.rpc;

import static com.dremio.exec.rpc.TestChannelExceptionHandler.ChannelStatus.CLOSED;
import static com.dremio.exec.rpc.TestChannelExceptionHandler.ChannelStatus.OPEN;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestChannelExceptionHandler {

  private Channel channel;
  private ChannelHandlerContext ctx;

  @BeforeEach
  void setup() {
    channel = mock(Channel.class);

    ctx = mock(ChannelHandlerContext.class);
    when(ctx.channel()).thenReturn(channel);
  }

  @AfterEach
  void tearDown() {
    Mockito.reset(channel);
    Mockito.reset(ctx);
  }

  protected ChannelExceptionHandler createChannelExceptionHandler() {
    return new ChannelExceptionHandler();
  }

  @Test
  @DisplayName("When channel closed, do not close the context")
  void handleChannelClosed() throws Exception {
    whenChannel(CLOSED);

    Throwable throwable = new RuntimeException("Connection closed");
    ChannelExceptionHandler handler = createChannelExceptionHandler();
    handler.exceptionCaught(ctx, throwable);

    verify(ctx, never()).close();
  }

  @Test
  @DisplayName("When channel closed and null message exception, do not close the context")
  void handleChannelClosedNullMessageException() throws Exception {
    whenChannel(CLOSED);

    Throwable throwable = new RuntimeException((String) null);
    ChannelExceptionHandler handler = createChannelExceptionHandler();
    handler.exceptionCaught(ctx, throwable);

    verify(ctx, never()).close();
  }

  @Test
  @DisplayName("When channel open, and reset by peer, do not close the context")
  void handleChannelOpenConnectionResetByPeer() throws Exception {
    whenChannel(OPEN);

    Throwable throwable = new RuntimeException("Connection reset by peer");
    ChannelExceptionHandler handler = createChannelExceptionHandler();
    handler.exceptionCaught(ctx, throwable);

    verify(ctx, never()).close();
  }

  @Test
  @DisplayName("When channel open, with null exception message, do close the context")
  void handleChannelOpenNullMessageException() throws Exception {
    whenChannel(OPEN);

    Throwable throwable = new RuntimeException((String) null);
    ChannelExceptionHandler handler = createChannelExceptionHandler();
    handler.exceptionCaught(ctx, throwable);

    verify(ctx, times(1)).close();
  }

  @Test
  @DisplayName("When channel open and reset by peer, do close the context")
  void handleChannelOpenConnectionNotResetedByPeer() throws Exception {
    whenChannel(OPEN);

    Throwable throwable = new RuntimeException("Connection closed");
    ChannelExceptionHandler handler = createChannelExceptionHandler();
    handler.exceptionCaught(ctx, throwable);

    verify(ctx, times(1)).close();
  }

  enum ChannelStatus {
    OPEN,
    CLOSED
  }

  private void whenChannel(ChannelStatus channelStatus) {
    switch (channelStatus) {
      case OPEN:
        when(channel.isOpen()).thenReturn(true);
        break;
      case CLOSED:
        when(channel.isOpen()).thenReturn(false);
        break;
    }
  }
}
