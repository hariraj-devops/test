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
package com.dremio.sabot.op.sender.single;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.exec.physical.config.SingleSender;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.proto.ExecProtos.FragmentHandle;
import com.dremio.exec.proto.ExecRPC.FragmentStreamComplete;
import com.dremio.exec.record.FragmentWritableBatch;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.testing.ControlsInjector;
import com.dremio.exec.testing.ControlsInjectorFactory;
import com.dremio.sabot.exec.context.MetricDef;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.rpc.AccountingExecTunnel;
import com.dremio.sabot.exec.rpc.TunnelProvider;
import com.dremio.sabot.op.sender.BaseSender;
import com.dremio.sabot.op.sender.SenderLatencyTracker;
import com.dremio.sabot.op.spi.TerminalOperator;
import com.google.common.base.Preconditions;
import org.apache.arrow.memory.OutOfMemoryException;

public class SingleSenderOperator extends BaseSender {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(SingleSenderOperator.class);
  private static final ControlsInjector injector =
      ControlsInjectorFactory.getInjector(SingleSenderOperator.class);

  private final FragmentHandle oppositeHandle;
  private final OperatorContext context;
  private final AccountingExecTunnel tunnel;
  private final FragmentHandle handle;
  private final int recMajor;
  private final SenderLatencyTracker latencyTracker;

  private State state = State.NEEDS_SETUP;
  private VectorAccessible incoming;

  public enum Metric implements MetricDef {
    BYTES_SENT,
    BATCHES_SENT,
    SUM_ACK_MILLIS,
    MAX_ACK_MILLIS;

    @Override
    public int metricId() {
      return ordinal();
    }
  }

  public SingleSenderOperator(
      TunnelProvider tunnelProvider, OperatorContext context, SingleSender config)
      throws OutOfMemoryException {
    super(config);
    this.context = context;
    this.handle = context.getFragmentHandle();
    this.recMajor = config.getReceiverMajorFragmentId();
    this.oppositeHandle =
        handle.toBuilder()
            .setMajorFragmentId(config.getReceiverMajorFragmentId())
            .setMinorFragmentId(config.getReceiverMinorFragmentId())
            .build();

    NodeEndpoint ep = config.getDestinations(context.getEndpointsIndex()).get(0).getEndpoint();
    this.tunnel = tunnelProvider.getExecTunnel(ep);
    this.latencyTracker = new SenderLatencyTracker();
  }

  @Override
  public void consumeData(int records) {
    Preconditions.checkArgument(records > 0);
    final FragmentWritableBatch batch =
        FragmentWritableBatch.create(
            handle.getQueryId(),
            handle.getMajorFragmentId(),
            handle.getMinorFragmentId(),
            recMajor,
            incoming,
            oppositeHandle.getMinorFragmentId());
    updateStats(batch);
    context.getStats().startWait();
    try {
      tunnel.sendRecordBatch(batch, latencyTracker.getLatencyObserver());
    } finally {
      context.getStats().stopWait();
    }
  }

  @Override
  public State getState() {
    return state;
  }

  @Override
  public void setup(VectorAccessible incoming) {
    state.is(State.NEEDS_SETUP);
    this.incoming = incoming;
    checkSchema(incoming.getSchema());
    state = State.CAN_CONSUME;
  }

  @Override
  public <OUT, IN, EXCEP extends Throwable> OUT accept(
      OperatorVisitor<OUT, IN, EXCEP> visitor, IN value) throws EXCEP {
    return visitor.visitTerminalOperator(this, value);
  }

  @Override
  public void noMoreToConsume() {
    FragmentStreamComplete completionMessage =
        FragmentStreamComplete.newBuilder()
            .setQueryId(handle.getQueryId())
            .setSendingMajorFragmentId(handle.getMajorFragmentId())
            .setSendingMinorFragmentId(handle.getMinorFragmentId())
            .setReceivingMajorFragmentId(oppositeHandle.getMajorFragmentId())
            .addReceivingMinorFragmentId(oppositeHandle.getMinorFragmentId())
            .build();
    tunnel.sendStreamComplete(completionMessage);
    state = State.DONE;
  }

  @Override
  public void close() throws Exception {
    context.getStats().setLongStat(Metric.SUM_ACK_MILLIS, latencyTracker.getSumAckMillis());
    context.getStats().setLongStat(Metric.MAX_ACK_MILLIS, latencyTracker.getMaxAckMillis());
  }

  private void updateStats(FragmentWritableBatch writableBatch) {
    context.getStats().addLongStat(Metric.BYTES_SENT, writableBatch.getByteCount());
    context.getStats().addLongStat(Metric.BATCHES_SENT, 1);
  }

  @Override
  public void receivingFragmentFinished(FragmentHandle handle) {
    state = State.DONE;
  }

  public static class Creator implements TerminalOperator.Creator<SingleSender> {

    @Override
    public TerminalOperator create(
        TunnelProvider tunnelProvider, OperatorContext context, SingleSender config)
        throws ExecutionSetupException {
      return new SingleSenderOperator(tunnelProvider, context, config);
    }
  }
}
