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
package com.dremio.sabot.exec.fragment;

import com.dremio.common.AutoCloseables;
import com.dremio.common.AutoCloseables.RollbackCloseable;
import com.dremio.common.DeferredException;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.exceptions.ErrorHelper;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.config.DremioConfig;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.compile.CodeCompiler;
import com.dremio.exec.expr.ExpressionSplitCache;
import com.dremio.exec.expr.fn.FunctionImplementationRegistry;
import com.dremio.exec.planner.PhysicalPlanReader;
import com.dremio.exec.planner.fragment.CachedFragmentReader;
import com.dremio.exec.planner.fragment.EndpointsIndex;
import com.dremio.exec.planner.fragment.PlanFragmentFull;
import com.dremio.exec.proto.CoordExecRPC.FragmentAssignment;
import com.dremio.exec.proto.CoordExecRPC.MajorFragmentAssignment;
import com.dremio.exec.proto.CoordExecRPC.PlanFragmentMajor;
import com.dremio.exec.proto.CoordExecRPC.SchedulingInfo;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.proto.ExecProtos.FragmentHandle;
import com.dremio.exec.server.NodeDebugContextProvider;
import com.dremio.exec.server.options.FragmentOptionManager;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.testing.ControlsInjector;
import com.dremio.exec.testing.ControlsInjectorFactory;
import com.dremio.exec.testing.ExecutionControls;
import com.dremio.options.OptionList;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValue;
import com.dremio.options.impl.DefaultOptionManager;
import com.dremio.options.impl.OptionManagerWrapper;
import com.dremio.sabot.driver.OperatorCreatorRegistry;
import com.dremio.sabot.exec.EventProvider;
import com.dremio.sabot.exec.FragmentExecutors;
import com.dremio.sabot.exec.FragmentTicket;
import com.dremio.sabot.exec.FragmentWorkManager.ExecConnectionCreator;
import com.dremio.sabot.exec.MaestroProxy;
import com.dremio.sabot.exec.QueriesClerk;
import com.dremio.sabot.exec.QueryTicket;
import com.dremio.sabot.exec.context.ContextInformation;
import com.dremio.sabot.exec.context.ContextInformationFactory;
import com.dremio.sabot.exec.context.FragmentStats;
import com.dremio.sabot.exec.context.StatusHandler;
import com.dremio.sabot.exec.cursors.FileCursorManagerFactory;
import com.dremio.sabot.exec.heap.HeapLowMemController;
import com.dremio.sabot.exec.rpc.TunnelProvider;
import com.dremio.sabot.memory.MemoryArbiter;
import com.dremio.sabot.threads.SendingAccountor;
import com.dremio.sabot.threads.sharedres.SharedResourceGroup;
import com.dremio.sabot.threads.sharedres.SharedResourceManager;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.jobresults.client.JobResultsClientFactory;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.spill.SpillService;
import com.dremio.services.jobresults.common.JobResultsTunnel;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import javax.inject.Provider;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;

/** Singleton utility to help in constructing a FragmentExecutor. */
public class FragmentExecutorBuilder {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(FragmentExecutorBuilder.class);
  private static final ControlsInjector injector =
      ControlsInjectorFactory.getInjector(FragmentExecutorBuilder.class);

  static final String PIPELINE_RES_GRP = "pipeline";
  static final String WORK_QUEUE_RES_GRP = "work-queue";
  static final String OOB_QUEUE = "oob-queue";

  @VisibleForTesting public static final String INJECTOR_DO_WORK = "injectOOMOnBuild";

  private final QueriesClerk clerk;
  private final FragmentExecutors fragmentExecutors;
  private final CoordinationProtos.NodeEndpoint nodeEndpoint;
  private final MaestroProxy maestroProxy;
  private final SabotConfig config;
  private final DremioConfig dremioConfig;
  private final ClusterCoordinator coord;
  private final ExecutorService executorService;
  private final OptionManager optionManager;
  private final ExecConnectionCreator dataCreator;
  private final NamespaceService namespace;

  private final OperatorCreatorRegistry opCreator;
  private final FunctionImplementationRegistry funcRegistry;
  private final FunctionImplementationRegistry decimalFuncRegistry;
  private final CodeCompiler compiler;
  private final ExpressionSplitCache expressionSplitCache;
  private final PhysicalPlanReader planReader;
  private final Set<ClusterCoordinator.Role> roles;
  private final CatalogService sources;
  private final ContextInformationFactory contextInformationFactory;
  private final NodeDebugContextProvider nodeDebugContextProvider;
  private final SpillService spillService;
  private final Provider<JobResultsClientFactory> jobResultsClientFactoryProvider;
  private final Provider<CoordinationProtos.NodeEndpoint> nodeEndpointProvider;
  private final HeapLowMemController heapLowMemController;

  public FragmentExecutorBuilder(
      QueriesClerk clerk,
      FragmentExecutors fragmentExecutors,
      CoordinationProtos.NodeEndpoint nodeEndpoint,
      MaestroProxy maestroProxy,
      SabotConfig config,
      DremioConfig dremioConfig,
      ClusterCoordinator coord,
      ExecutorService executorService,
      OptionManager optionManager,
      ExecConnectionCreator dataCreator,
      OperatorCreatorRegistry operatorCreatorRegistry,
      PhysicalPlanReader planReader,
      NamespaceService namespace,
      CatalogService sources,
      ContextInformationFactory contextInformationFactory,
      FunctionImplementationRegistry functions,
      FunctionImplementationRegistry decimalFunctions,
      NodeDebugContextProvider nodeDebugContextProvider,
      SpillService spillService,
      CodeCompiler codeCompiler,
      Set<ClusterCoordinator.Role> roles,
      Provider<JobResultsClientFactory> jobResultsClientFactoryProvider,
      Provider<CoordinationProtos.NodeEndpoint> nodeEndpointProvider,
      ExpressionSplitCache expressionSplitCache,
      HeapLowMemController heapLowMemController) {
    this.clerk = clerk;
    this.fragmentExecutors = fragmentExecutors;
    this.nodeEndpoint = nodeEndpoint;
    this.maestroProxy = maestroProxy;
    this.config = config;
    this.dremioConfig = dremioConfig;
    this.coord = coord;
    this.executorService = executorService;
    this.optionManager = optionManager;
    this.dataCreator = dataCreator;
    this.expressionSplitCache = expressionSplitCache;
    this.namespace = namespace;
    this.planReader = planReader;
    this.opCreator = operatorCreatorRegistry;
    this.funcRegistry = functions;
    this.decimalFuncRegistry = decimalFunctions;
    this.nodeEndpointProvider = nodeEndpointProvider;
    this.compiler = codeCompiler;
    this.roles = roles;
    this.sources = sources;
    this.contextInformationFactory = contextInformationFactory;
    this.nodeDebugContextProvider = nodeDebugContextProvider;
    this.spillService = spillService;
    this.jobResultsClientFactoryProvider = jobResultsClientFactoryProvider;
    this.heapLowMemController = heapLowMemController;
  }

  public FragmentExecutors getFragmentExecutors() {
    return fragmentExecutors;
  }

  public CoordinationProtos.NodeEndpoint getNodeEndpoint() {
    return nodeEndpoint;
  }

  public PhysicalPlanReader getPlanReader() {
    return planReader;
  }

  public QueriesClerk getClerk() {
    return clerk;
  }

  public FragmentExecutor build(
      final QueryTicket queryTicket,
      final PlanFragmentFull fragment,
      final int schedulingWeight,
      final MemoryArbiter memoryArbiter,
      final EventProvider eventProvider,
      final SchedulingInfo schedulingInfo,
      final CachedFragmentReader cachedReader)
      throws Exception {

    final AutoCloseableList services = new AutoCloseableList();
    final PlanFragmentMajor major = fragment.getMajor();

    try (RollbackCloseable commit = new RollbackCloseable(services)) {
      final OptionList list = cachedReader.readOptions(fragment);
      final FragmentHandle handle = fragment.getHandle();
      final FragmentTicket ticket =
          services.protect(clerk.newFragmentTicket(queryTicket, fragment, schedulingInfo));
      logger.debug("Getting initial memory allocation of {}", fragment.getMemInitial());
      logger.debug("Fragment max allocation: {}", fragment.getMemMax());

      // Add the fragment context to the root allocator.
      final BufferAllocator allocator;
      try {
        long reservation = fragment.getMemInitial();
        long limit = fragment.getMemMax();
        if (optionManager.getOption(ExecConstants.ENABLE_SPILLABLE_OPERATORS)) {
          reservation = 0;
          limit = Long.MAX_VALUE;
        }
        allocator =
            ticket.newChildAllocator(
                "frag:" + QueryIdHelper.getFragmentId(fragment.getHandle()), reservation, limit);
        Preconditions.checkNotNull(allocator, "Unable to acquire allocator");
        services.protect(allocator);
      } catch (final OutOfMemoryException e) {
        String additionalInfo =
            "Fragment" + handle.getMajorFragmentId() + ":" + handle.getMinorFragmentId();
        UserException.Builder builder = UserException.memoryError(e);
        nodeDebugContextProvider.addMemoryContext(builder, e);
        builder.addContext(additionalInfo);
        throw builder.build(logger);
      } catch (final Throwable e) {
        throw new ExecutionSetupException(
            "Failure while getting memory allocator for fragment.", e);
      }

      try {
        final FragmentStats stats =
            new FragmentStats(
                allocator,
                handle,
                fragment.getAssignment(),
                optionManager.getOption(ExecConstants.STORE_IO_TIME_WARN_THRESH_MILLIS));
        final SharedResourceManager sharedResources =
            SharedResourceManager.newBuilder()
                .addGroup(PIPELINE_RES_GRP)
                .addGroup(WORK_QUEUE_RES_GRP)
                .build();

        if (!roles.contains(ClusterCoordinator.Role.COORDINATOR)) {
          // set the SYSTEM options in the system option manager, but only do it on non-coordinator
          // nodes
          boolean enableHeapMonitoringOptionPresent = false;
          boolean thresholdOptionPresent = false;

          for (OptionValue option : list.getSystemOptions()) {
            if (ExecConstants.EXECUTOR_ENABLE_HEAP_MONITORING
                .getOptionName()
                .equals(option.getName())) {
              enableHeapMonitoringOptionPresent = true;
            } else if (ExecConstants.EXECUTOR_HEAP_MONITORING_CLAWBACK_THRESH_PERCENTAGE
                .getOptionName()
                .equals(option.getName())) {
              thresholdOptionPresent = true;
            }
            optionManager.setOption(option);
          }

          // Deleting heap monitor related options if not present in system options.
          // This will ensure that heap monitor system options which were reset
          // (to default) on coordinator will be also reset on non-coordinator nodes.
          if (!enableHeapMonitoringOptionPresent) {
            optionManager.deleteOption(
                ExecConstants.EXECUTOR_ENABLE_HEAP_MONITORING.getOptionName(),
                OptionValue.OptionType.SYSTEM);
          }

          if (!thresholdOptionPresent) {
            optionManager.deleteOption(
                ExecConstants.EXECUTOR_HEAP_MONITORING_CLAWBACK_THRESH_PERCENTAGE.getOptionName(),
                OptionValue.OptionType.SYSTEM);
          }
        }
        // add the remaining options (QUERY, SESSION) to the fragment option manager
        final FragmentOptionManager fragmentOptionManager =
            new FragmentOptionManager(optionManager.getOptionValidatorListing(), list);
        final OptionManager fragmentOptions =
            OptionManagerWrapper.Builder.newBuilder()
                .withOptionManager(
                    new DefaultOptionManager(optionManager.getOptionValidatorListing()))
                .withOptionManager(fragmentOptionManager)
                .build();

        final FlushableSendingAccountor flushable =
            new FlushableSendingAccountor(sharedResources.getGroup(PIPELINE_RES_GRP));
        final ExecutionControls controls =
            new ExecutionControls(fragmentOptions, fragment.getAssignment());

        final ContextInformation contextInfo =
            contextInformationFactory.newContextFactory(major.getCredentials(), major.getContext());

        // create rpc connections
        final JobResultsTunnel jobResultsTunnel =
            jobResultsClientFactoryProvider
                .get()
                .getJobResultsClient(
                    major.getForeman(),
                    allocator,
                    QueryIdHelper.getFragmentId(fragment.getHandle()),
                    QueryIdHelper.getQueryIdentifier(fragment.getHandle()))
                .getTunnel();
        final DeferredException exception = new DeferredException();
        final StatusHandler handler = new StatusHandler(exception);
        final FileCursorManagerFactory fileCursorManagerFactory =
            maestroProxy.getFileCursorMangerFactory(fragment.getHandle().getQueryId());
        int outstandingRPCsPerTunnel =
            (int) optionManager.getOption(ExecConstants.OUTSTANDING_RPCS_PER_TUNNEL);
        final TunnelProvider tunnelProvider =
            getTunnelProvider(
                flushable.getAccountor(),
                jobResultsTunnel,
                dataCreator,
                handler,
                sharedResources.getGroup(PIPELINE_RES_GRP),
                fileCursorManagerFactory,
                outstandingRPCsPerTunnel,
                major);

        queryTicket.setEndpointsAndTunnelProvider(
            cachedReader.getPlanFragmentsIndex().getEndpointsIndex(), tunnelProvider);

        final OperatorContextCreator creator =
            getOperatorContextCreator(
                stats,
                allocator,
                compiler,
                config,
                dremioConfig,
                handle,
                controls,
                funcRegistry,
                decimalFuncRegistry,
                namespace,
                fragmentOptions,
                this,
                executorService,
                spillService,
                contextInfo,
                nodeDebugContextProvider,
                tunnelProvider,
                major.getAllAssignmentList(),
                cachedReader.getPlanFragmentsIndex().getEndpointsIndex(),
                nodeEndpointProvider,
                major.getExtFragmentAssignmentsList(),
                expressionSplitCache,
                heapLowMemController);

        final FragmentStatusReporter statusReporter =
            new FragmentStatusReporter(
                fragment.getHandle(), schedulingWeight, stats, maestroProxy, allocator);
        final FragmentExecutor executor =
            new FragmentExecutor(
                statusReporter,
                config,
                controls,
                fragment,
                schedulingWeight,
                memoryArbiter,
                coord,
                cachedReader,
                sharedResources,
                opCreator,
                allocator,
                contextInfo,
                creator,
                funcRegistry,
                decimalFuncRegistry,
                fileCursorManagerFactory,
                tunnelProvider,
                flushable,
                fragmentOptions,
                stats,
                ticket,
                sources,
                exception,
                eventProvider,
                spillService,
                nodeDebugContextProvider);
        commit.commit();

        injector.injectChecked(controls, INJECTOR_DO_WORK, OutOfMemoryException.class);

        return executor;
      } catch (Exception e) {
        UserException.Builder builder;
        String locationInfo =
            String.format(
                "Location %d:%d", handle.getMajorFragmentId(), handle.getMinorFragmentId());
        String additionalInfo =
            String.format(
                "Failure while constructing fragment. " + "Location %d:%d",
                handle.getMajorFragmentId(), handle.getMinorFragmentId());
        if (ErrorHelper.isDirectMemoryException(e)) {
          builder = UserException.memoryError(e);
          nodeDebugContextProvider.addMemoryContext(builder, e);
          builder.addContext(locationInfo);
        } else if (ErrorHelper.isJavaHeapOutOfMemory(e)) {
          builder = UserException.memoryError(e);
          nodeDebugContextProvider.addHeapMemoryContext(builder, e);
          builder.addContext(locationInfo);
        } else {
          builder = UserException.systemError(e).message(additionalInfo);
        }
        nodeDebugContextProvider.addErrorOrigin(builder);
        throw builder.build(logger);
      }
    }
  }

  protected TunnelProvider getTunnelProvider(
      SendingAccountor accountor,
      JobResultsTunnel jobResultsTunnel,
      ExecConnectionCreator dataCreator,
      StatusHandler handler,
      SharedResourceGroup sharedResourceGroup,
      FileCursorManagerFactory fileCursorManagerFactory,
      int outstandingRPCsPerTunnel,
      PlanFragmentMajor planFragmentMajor) {
    return new TunnelProviderImpl(
        accountor,
        jobResultsTunnel,
        dataCreator,
        handler,
        sharedResourceGroup,
        fileCursorManagerFactory,
        outstandingRPCsPerTunnel);
  }

  protected OperatorContextCreator getOperatorContextCreator(
      FragmentStats stats,
      BufferAllocator allocator,
      CodeCompiler compiler,
      SabotConfig config,
      DremioConfig dremioConfig,
      FragmentHandle handle,
      ExecutionControls controls,
      FunctionImplementationRegistry funcRegistry,
      FunctionImplementationRegistry decimalFuncRegistry,
      NamespaceService namespace,
      OptionManager fragmentOptions,
      FragmentExecutorBuilder fragmentExecutorBuilder,
      ExecutorService executorService,
      SpillService spillService,
      ContextInformation contextInfo,
      NodeDebugContextProvider nodeDebugContextProvider,
      TunnelProvider tunnelProvider,
      List<FragmentAssignment> allAssignmentList,
      EndpointsIndex endpointsIndex,
      Provider<NodeEndpoint> nodeEndpointProvider,
      List<MajorFragmentAssignment> extFragmentAssignmentsList,
      ExpressionSplitCache expressionSplitCache,
      HeapLowMemController heapLowMemController) {
    return new OperatorContextCreator(
        stats,
        allocator,
        compiler,
        config,
        dremioConfig,
        handle,
        controls,
        funcRegistry,
        decimalFuncRegistry,
        namespace,
        fragmentOptions,
        fragmentExecutorBuilder,
        executorService,
        spillService,
        contextInfo,
        nodeDebugContextProvider,
        tunnelProvider,
        allAssignmentList,
        endpointsIndex,
        nodeEndpointProvider,
        extFragmentAssignmentsList,
        expressionSplitCache,
        heapLowMemController);
  }

  @SuppressWarnings("serial")
  private class AutoCloseableList extends ArrayList<AutoCloseable> implements AutoCloseable {
    private final List<AutoCloseable> items = new ArrayList<>();

    public <T extends AutoCloseable> T protect(T impl) {
      items.add(impl);
      return impl;
    }

    @Override
    public void close() throws Exception {
      Collections.reverse(items);
      AutoCloseables.close(items);
    }
  }
}
