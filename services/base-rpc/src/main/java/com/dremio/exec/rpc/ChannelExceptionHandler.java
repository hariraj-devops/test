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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

/**
 * A handler for managing exceptions that occur in a Netty channel.
 *
 * <p>Handle catching exceptions that occur during RPC communication. If the channel is still open
 * when an exception occurs, it is closed.
 */
public class ChannelExceptionHandler implements ChannelHandler {
  static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ChannelExceptionHandler.class);

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {}

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {}

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (!ctx.channel().isOpen() || "Connection reset by peer".equals(cause.getMessage())) {
      logger.warn(
          "Exception occurred with closed channel.{}",
          getConnectionIdToAppend(getConnectionId()),
          cause);
    } else {
      logger.error(
          "Exception in RPC communication. Closing connection.{}",
          getConnectionIdToAppend(getConnectionId()),
          cause);
      ctx.close();
    }
  }

  protected String getConnectionId() {
    return null;
  }

  private String getConnectionIdToAppend(String connectionId) {
    if (connectionId != null) {
      return " Connection: " + connectionId;
    }
    return "";
  }
}
