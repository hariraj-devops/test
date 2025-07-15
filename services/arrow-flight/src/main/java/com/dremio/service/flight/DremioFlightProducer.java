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

package com.dremio.service.flight;

import static com.dremio.service.flight.AbstractSessionServiceFlightManager.SESSION_ID_KEY;
import static com.dremio.service.flight.utils.DremioFlightSqlInfoUtils.getNewSqlInfoBuilder;
import static com.google.protobuf.Any.pack;
import static org.apache.arrow.flight.sql.impl.FlightSql.ActionClosePreparedStatementRequest;
import static org.apache.arrow.flight.sql.impl.FlightSql.ActionCreatePreparedStatementRequest;
import static org.apache.arrow.flight.sql.impl.FlightSql.ActionCreatePreparedStatementResult;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandGetCatalogs;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandGetCrossReference;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandGetDbSchemas;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandGetExportedKeys;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandGetImportedKeys;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandGetPrimaryKeys;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandGetSqlInfo;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandGetTableTypes;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandGetTables;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandGetXdbcTypeInfo;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandPreparedStatementQuery;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandPreparedStatementUpdate;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandStatementQuery;
import static org.apache.arrow.flight.sql.impl.FlightSql.CommandStatementUpdate;
import static org.apache.arrow.flight.sql.impl.FlightSql.TicketStatementQuery;

import com.dremio.common.exceptions.UserException;
import com.dremio.context.RequestContext;
import com.dremio.context.TenantContext;
import com.dremio.context.UserContext;
import com.dremio.exec.proto.UserProtos;
import com.dremio.exec.work.protector.UserWorker;
import com.dremio.options.OptionManager;
import com.dremio.sabot.rpc.user.ChangeTrackingUserSession;
import com.dremio.sabot.rpc.user.SessionOptionValue;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.flight.error.mapping.DremioFlightErrorMapper;
import com.dremio.service.flight.impl.FlightPreparedStatement;
import com.dremio.service.flight.impl.FlightWorkManager;
import com.dremio.service.flight.impl.FlightWorkManager.RunQueryResponseHandlerFactory;
import com.dremio.service.flight.utils.DremioFlightPreparedStatementUtils;
import com.dremio.service.users.User;
import com.dremio.service.users.UserNotFoundException;
import com.dremio.service.users.UserService;
import com.dremio.service.users.proto.UID;
import com.dremio.service.usersessions.UserSessionService;
import com.dremio.service.usersessions.UserSessionService.UserSessionData;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.grpc.Status;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import javax.inject.Provider;
import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.ActionType;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.CloseSessionResult;
import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.DremioToFlightSessionOptionValueConverter;
import org.apache.arrow.flight.FlightConstants;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.GetSessionOptionsResult;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SchemaResult;
import org.apache.arrow.flight.SessionOptionValueToProtocolVisitor;
import org.apache.arrow.flight.SetSessionOptionsRequest;
import org.apache.arrow.flight.SetSessionOptionsResult;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.flight.impl.Flight;
import org.apache.arrow.flight.sql.FlightSqlProducer;
import org.apache.arrow.flight.sql.FlightSqlUtils;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A FlightProducer implementation which exposes Dremio's catalog and produces results from SQL
 * queries.
 */
public class DremioFlightProducer implements FlightSqlProducer {
  private static final Logger logger = LoggerFactory.getLogger(DremioFlightProducer.class);

  private final FlightWorkManager flightWorkManager;
  private final Optional<Location> location;
  private final DremioFlightSessionsManager sessionsManager;
  private final BufferAllocator allocator;
  private final Provider<FlightRequestContextDecorator> requestContextDecorator;
  private final Provider<UserService> userServiceProvider;

  public DremioFlightProducer(
      Optional<Location> location,
      DremioFlightSessionsManager sessionsManager,
      Provider<UserWorker> workerProvider,
      Provider<OptionManager> optionManagerProvider,
      BufferAllocator allocator,
      Provider<FlightRequestContextDecorator> requestContextDecorator,
      RunQueryResponseHandlerFactory runQueryResponseHandlerFactory,
      Provider<UserService> userServiceProvider) {
    this.location = location;
    this.sessionsManager = sessionsManager;
    this.allocator = allocator;
    this.requestContextDecorator = requestContextDecorator;
    this.userServiceProvider = userServiceProvider;

    flightWorkManager =
        new FlightWorkManager(
            workerProvider, optionManagerProvider, runQueryResponseHandlerFactory);
  }

  @Override
  public void getStream(
      CallContext callContext, Ticket ticket, ServerStreamListener serverStreamListener) {
    runWithRequestContext(
        callContext,
        () -> {
          if (isFlightSqlTicket(ticket)) {
            FlightSqlProducer.super.getStream(callContext, ticket, serverStreamListener);
            return null;
          }

          getStreamLegacy(callContext, ticket, serverStreamListener);
          return null;
        });
  }

  private void getStreamLegacy(
      CallContext callContext, Ticket ticket, ServerStreamListener serverStreamListener) {
    try {
      getUserSessionData(callContext);
      final TicketContent.PreparedStatementTicket preparedStatementTicket =
          TicketContent.PreparedStatementTicket.parseFrom(ticket.getBytes());

      final UserProtos.PreparedStatementHandle preparedStatementHandle =
          preparedStatementTicket.getHandle();
      runPreparedStatement(callContext, serverStreamListener, preparedStatementHandle);
    } catch (InvalidProtocolBufferException ex) {
      final RuntimeException error =
          CallStatus.INVALID_ARGUMENT
              .withCause(ex)
              .withDescription("Invalid PreparedStatementTicket used in getStream.")
              .toRuntimeException();
      serverStreamListener.error(error);
      throw error;
    } catch (final Exception ex) {
      handleStreamException(ex, serverStreamListener);
    }
  }

  @Override
  public void getStreamPreparedStatement(
      CommandPreparedStatementQuery commandPreparedStatementQuery,
      CallContext callContext,
      ServerStreamListener serverStreamListener) {
    try {
      getUserSessionData(callContext);
      final UserProtos.PreparedStatementArrow preparedStatement =
          UserProtos.PreparedStatementArrow.parseFrom(
              commandPreparedStatementQuery.getPreparedStatementHandle());

      runPreparedStatement(
          callContext,
          serverStreamListener,
          preparedStatement.getServerHandle(),
          preparedStatement.getParametersList());
    } catch (InvalidProtocolBufferException e) {
      final FlightRuntimeException ex =
          CallStatus.INVALID_ARGUMENT
              .withCause(e)
              .withDescription("Invalid PreparedStatementHandle used in getStream.")
              .toRuntimeException();
      serverStreamListener.error(ex);
      throw ex;
    } catch (final Exception ex) {
      handleStreamException(ex, serverStreamListener);
    }
  }

  @Override
  public SchemaResult getSchema(CallContext context, FlightDescriptor descriptor) {
    return runWithRequestContext(
        context,
        () -> {
          if (isFlightSqlCommand(descriptor)) {
            return FlightSqlProducer.super.getSchema(context, descriptor);
          }

          return getSchemaLegacy(context, descriptor);
        });
  }

  private SchemaResult getSchemaLegacy(CallContext context, FlightDescriptor descriptor) {
    getUserSessionData(context);
    FlightInfo info = this.getFlightInfo(context, descriptor);
    return new SchemaResult(info.getSchema());
  }

  @Override
  public void listFlights(
      CallContext callContext, Criteria criteria, StreamListener<FlightInfo> streamListener) {
    runWithRequestContext(
        callContext,
        () -> {
          throw CallStatus.UNIMPLEMENTED
              .withDescription("listFlights is not implemented.")
              .toRuntimeException();
        });
  }

  @Override
  public FlightInfo getFlightInfo(CallContext callContext, FlightDescriptor flightDescriptor) {
    return runWithRequestContext(
        callContext,
        () -> {
          if (isFlightSqlCommand(flightDescriptor)) {
            return FlightSqlProducer.super.getFlightInfo(callContext, flightDescriptor);
          }

          return getFlightInfoLegacy(callContext, flightDescriptor);
        });
  }

  private FlightInfo getFlightInfoLegacy(
      CallContext callContext, FlightDescriptor flightDescriptor) {
    final UserSession session = getUserSessionData(callContext).getSession();

    final FlightPreparedStatement flightPreparedStatement =
        flightWorkManager.createPreparedStatement(
            flightDescriptor, callContext::isCancelled, session);

    return flightPreparedStatement.getFlightInfoLegacy(location, flightDescriptor);
  }

  @Override
  public FlightInfo getFlightInfoPreparedStatement(
      CommandPreparedStatementQuery commandPreparedStatementQuery,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);
    final UserProtos.PreparedStatementArrow preparedStatement;

    try {
      preparedStatement =
          UserProtos.PreparedStatementArrow.parseFrom(
              commandPreparedStatementQuery.getPreparedStatementHandle());
    } catch (InvalidProtocolBufferException e) {
      throw CallStatus.INVALID_ARGUMENT
          .withDescription("Invalid PreparedStatementHandle used in getFlightInfo.")
          .toRuntimeException();
    }

    final Schema schema = FlightPreparedStatement.buildSchema(preparedStatement.getArrowSchema());
    return getFlightInfoForFlightSqlCommands(
        commandPreparedStatementQuery, flightDescriptor, schema);
  }

  @Override
  public Runnable acceptPut(
      CallContext callContext,
      FlightStream flightStream,
      StreamListener<PutResult> streamListener) {
    return runWithRequestContext(
        callContext,
        () -> {
          if (isFlightSqlCommand(flightStream.getDescriptor())) {
            return FlightSqlProducer.super.acceptPut(callContext, flightStream, streamListener);
          }

          throw CallStatus.UNIMPLEMENTED
              .withDescription("acceptPut is not implemented.")
              .toRuntimeException();
        });
  }

  @Override
  public void doAction(
      CallContext callContext, Action action, StreamListener<Result> streamListener) {
    runWithRequestContext(
        callContext,
        () -> {
          if (isFlightSqlAction(action)) {
            FlightSqlProducer.super.doAction(callContext, action, streamListener);
            return null;
          }

          logger.debug("DremioFlightProducer.doAction checking action type");
          try {
            if (action.getType().equals(FlightConstants.SET_SESSION_OPTIONS.getType())) {
              logger.debug("DremioFlightProducer.doAction action type set-session-option");
              final SetSessionOptionsRequest request =
                  SetSessionOptionsRequest.deserialize(ByteBuffer.wrap(action.getBody()));
              doActionSetSessionOption(request, callContext, streamListener);
              return null;
            }
            if (action.getType().equals(FlightConstants.GET_SESSION_OPTIONS.getType())) {
              logger.debug("DremioFlightProducer.doAction action type get-session-option");
              doActionGetSessionOptions(callContext, streamListener);
              return null;
            } else if (action.getType().equals(FlightConstants.CLOSE_SESSION.getType())) {
              logger.debug("DremioFlightProducer.doAction action type close-session");
              doActionCloseSession(callContext, streamListener);
              return null;
            }
          } catch (RuntimeException e) {
            streamListener.onError(e);
            throw (e);
          }

          logger.error("Invalid action type {}", action.getType());
          final RuntimeException exception =
              Status.UNIMPLEMENTED
                  .withDescription(
                      String.format("doAction action: %s is not implemented.", action.getType()))
                  .asRuntimeException();
          streamListener.onError(exception);
          throw exception;
        });
  }

  private void doActionSetSessionOption(
      SetSessionOptionsRequest request,
      CallContext callContext,
      StreamListener<Result> streamListener) {
    final CallHeaders incomingHeaders = retrieveHeadersFromCallContext(callContext);
    final UserSessionService.UserSessionData oldUserSessionData =
        sessionsManager.getUserSession(callContext.peerIdentity(), incomingHeaders);
    final Map<String, SessionOptionValue> sessionOptionsValueMap = new HashMap<>();

    if (oldUserSessionData != null) {
      sessionOptionsValueMap.putAll(oldUserSessionData.getSession().getSessionOptionsMap());
    }

    final Map<String, SetSessionOptionsResult.Error> errors =
        addSessionOptions(request.getSessionOptions(), sessionOptionsValueMap);

    // Do not create UserSession if errors in Session Options
    if (errors.isEmpty()) {
      if (oldUserSessionData != null) {
        doActionUpdateSession(oldUserSessionData, sessionOptionsValueMap);
      } else {
        doActionCreateSession(callContext, incomingHeaders, sessionOptionsValueMap);
      }
    }
    streamListener.onNext(new Result(new SetSessionOptionsResult(errors).serialize().array()));
    streamListener.onCompleted();
    logger.debug("DremioFlightProducer.doActionSetSessionOption success");
  }

  private void doActionGetSessionOptions(
      CallContext callContext, StreamListener<Result> streamListener) {
    final CallHeaders incomingHeaders = retrieveHeadersFromCallContext(callContext);
    UserSessionService.UserSessionData existingUserSessionData =
        sessionsManager.getUserSession(callContext.peerIdentity(), incomingHeaders);

    if (existingUserSessionData == null) {
      doActionCreateSession(callContext, incomingHeaders, new HashMap<>());
      // Re-fetch the session data in case default session options were set
      existingUserSessionData =
          sessionsManager.getUserSession(callContext.peerIdentity(), incomingHeaders);
    }
    final Map<String, org.apache.arrow.flight.SessionOptionValue> sessionOptions =
        DremioToFlightSessionOptionValueConverter.convert(
            existingUserSessionData.getSession().getSessionOptionsMap());

    streamListener.onNext(
        new Result(new GetSessionOptionsResult(sessionOptions).serialize().array()));
    streamListener.onCompleted();
    logger.debug("DremioFlightProducer.doActionGetSessionOption success");
  }

  private void doActionUpdateSession(
      UserSessionService.UserSessionData oldUserSessionData,
      Map<String, SessionOptionValue> sessionOptionsValueMap) {
    final UserSession oldUserSession = oldUserSessionData.getSession();
    final UserSession.Builder newUserSessionBuilder;
    newUserSessionBuilder = UserSession.Builder.newBuilderWithCopy(oldUserSession);
    newUserSessionBuilder.withSessionOptions(sessionOptionsValueMap);
    UserSession newUserSession = newUserSessionBuilder.build();
    UserSessionService.UserSessionData updatedUserSessionData =
        new UserSessionService.UserSessionData(
            newUserSession, oldUserSessionData.getVersion(), oldUserSessionData.getSessionId());
    logger.debug("DremioFlightProducer updateSession {}", oldUserSessionData.getSessionId());
    sessionsManager.updateSession(updatedUserSessionData);
  }

  private void doActionCreateSession(
      CallContext callContext,
      CallHeaders incomingHeaders,
      Map<String, SessionOptionValue> sessionOptionsValueMap) {
    try {
      logger.debug("DremioFlightProducer createUserSession");
      UserSessionService.UserSessionData sessionData =
          sessionsManager.createUserSession(
              callContext.peerIdentity(), incomingHeaders, Optional.of(sessionOptionsValueMap));
      sessionsManager.decorateResponse(callContext, sessionData);
    } catch (Exception e) {
      final String errorDescription = "Unable to create user session";
      logger.error(errorDescription, e);
      throw CallStatus.INTERNAL.withCause(e).withDescription(errorDescription).toRuntimeException();
    }
  }

  private Map<String, SetSessionOptionsResult.Error> addSessionOptions(
      Map<String, org.apache.arrow.flight.SessionOptionValue> sessionOptionsToAdd,
      Map<String, com.dremio.sabot.rpc.user.SessionOptionValue> sessionOptionsValueMap) {
    Map<String, SetSessionOptionsResult.Error> errors = new HashMap<>();

    for (Map.Entry<String, org.apache.arrow.flight.SessionOptionValue> entry :
        sessionOptionsToAdd.entrySet()) {
      final String key = entry.getKey();
      final Flight.SessionOptionValue value =
          SessionOptionValueToProtocolVisitor.toProtocol(entry.getValue());
      final Flight.SessionOptionValue.OptionValueCase valueCase = value.getOptionValueCase();
      switch (valueCase) {
        case STRING_VALUE:
          logger.debug(
              "ProxyFlightProducer.doActionSetSessionOption setting str option: {} {}",
              key,
              value.getStringValue());
          sessionOptionsValueMap.put(
              key,
              com.dremio.sabot.rpc.user.SessionOptionValue.Builder.newBuilder()
                  .setStringValue(value.getStringValue())
                  .build());
          break;
        case BOOL_VALUE:
          sessionOptionsValueMap.put(
              key,
              com.dremio.sabot.rpc.user.SessionOptionValue.Builder.newBuilder()
                  .setBoolValue(value.getBoolValue())
                  .build());
          break;
        case INT64_VALUE:
          sessionOptionsValueMap.put(
              key,
              com.dremio.sabot.rpc.user.SessionOptionValue.Builder.newBuilder()
                  .setInt64Value(value.getInt64Value())
                  .build());
          break;
        case DOUBLE_VALUE:
          sessionOptionsValueMap.put(
              key,
              com.dremio.sabot.rpc.user.SessionOptionValue.Builder.newBuilder()
                  .setDoubleValue(value.getDoubleValue())
                  .build());
          break;
        case STRING_LIST_VALUE:
          sessionOptionsValueMap.put(
              key,
              com.dremio.sabot.rpc.user.SessionOptionValue.Builder.newBuilder()
                  .setStringListValue(value.getStringListValueOrBuilder().getValuesList())
                  .build());
          break;
        default:
          final String errorDescription =
              String.format(
                  "OptionValueCase %d is not valid", value.getOptionValueCase().getNumber());
          logger.error(errorDescription);
          errors.put(
              entry.getKey(),
              new SetSessionOptionsResult.Error(SetSessionOptionsResult.ErrorValue.INVALID_VALUE));
      }
    }
    return errors;
  }

  private void doActionCloseSession(
      CallContext callContext, StreamListener<Result> streamListener) {
    final CallHeaders incomingHeaders = retrieveHeadersFromCallContext(callContext);
    UserSessionService.UserSessionData sessionData =
        sessionsManager.getUserSession(callContext.peerIdentity(), incomingHeaders);

    if (sessionData == null) {
      final String errorDescription =
          String.format(
              "User session %s does not exist",
              CookieUtils.getCookieValue(incomingHeaders, SESSION_ID_KEY));
      logger.error(errorDescription);
      throw CallStatus.NOT_FOUND.withDescription(errorDescription).toRuntimeException();
    }
    sessionsManager.closeSession(callContext, sessionData);
    final Result result =
        new Result(new CloseSessionResult(CloseSessionResult.Status.CLOSED).serialize().array());
    streamListener.onNext(result);
    streamListener.onCompleted();
  }

  @Override
  public void listActions(CallContext callContext, StreamListener<ActionType> streamListener) {
    runWithRequestContext(
        callContext,
        () -> {
          FlightSqlProducer.super.listActions(callContext, streamListener);
          return null;
        });
  }

  @Override
  public void doExchange(
      CallContext callContext, FlightStream reader, ServerStreamListener writer) {
    runWithRequestContext(
        callContext,
        () -> {
          FlightSqlProducer.super.doExchange(callContext, reader, writer);
          return null;
        });
  }

  @Override
  public void createPreparedStatement(
      ActionCreatePreparedStatementRequest actionCreatePreparedStatementRequest,
      CallContext callContext,
      StreamListener<Result> streamListener) {
    final String query = actionCreatePreparedStatementRequest.getQuery();

    try {
      final UserSession session = getUserSessionData(callContext).getSession();
      final FlightPreparedStatement flightPreparedStatement =
          flightWorkManager.createPreparedStatement(query, callContext::isCancelled, session);

      final ActionCreatePreparedStatementResult action = flightPreparedStatement.createAction();

      streamListener.onNext(new Result(pack(action).toByteArray()));
      streamListener.onCompleted();
    } catch (RuntimeException e) {
      streamListener.onError(e);
      throw e;
    }
  }

  @SuppressWarnings("IgnoredPureGetter")
  @Override
  public void closePreparedStatement(
      ActionClosePreparedStatementRequest actionClosePreparedStatementRequest,
      CallContext callContext,
      StreamListener<Result> listener) {
    try {
      getUserSessionData(callContext);
      // Do nothing other than validate the message.
      UserProtos.PreparedStatementHandle.parseFrom(
          actionClosePreparedStatementRequest.getPreparedStatementHandle());

    } catch (InvalidProtocolBufferException e) {
      logger.error("Invalid PreparedStatementHandle used in closePreparedStatement.");
      final FlightRuntimeException ex =
          CallStatus.INVALID_ARGUMENT
              .withDescription("Invalid PreparedStatementHandle used in closePreparedStatement.")
              .toRuntimeException();
      listener.onError(ex);
      throw ex;
    } catch (RuntimeException e) {
      listener.onError(e);
      throw e;
    }
    listener.onCompleted();
  }

  @Override
  public FlightInfo getFlightInfoStatement(
      CommandStatementQuery commandStatementQuery,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    final UserSession session = getUserSessionData(callContext).getSession();

    final FlightPreparedStatement flightPreparedStatement =
        flightWorkManager.createPreparedStatement(
            commandStatementQuery.getQuery(), callContext::isCancelled, session);

    final TicketStatementQuery ticket =
        TicketStatementQuery.newBuilder()
            .setStatementHandle(flightPreparedStatement.getServerHandle().toByteString())
            .build();

    final Schema schema = flightPreparedStatement.getSchema();
    return getFlightInfoForFlightSqlCommands(ticket, flightDescriptor, schema);
  }

  @Override
  public SchemaResult getSchemaStatement(
      CommandStatementQuery commandStatementQuery,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);
    final FlightInfo info = this.getFlightInfo(callContext, flightDescriptor);
    return new SchemaResult(info.getSchema());
  }

  @Override
  public void getStreamStatement(
      TicketStatementQuery ticketStatementQuery,
      CallContext callContext,
      ServerStreamListener serverStreamListener) {
    try {
      getUserSessionData(callContext);
      final UserProtos.PreparedStatementHandle preparedStatementHandle =
          UserProtos.PreparedStatementHandle.parseFrom(ticketStatementQuery.getStatementHandle());

      runPreparedStatement(callContext, serverStreamListener, preparedStatementHandle);
    } catch (InvalidProtocolBufferException e) {
      final FlightRuntimeException ex =
          CallStatus.INTERNAL
              .withCause(e)
              .withDescription("Invalid StatementHandle used in getStream.")
              .toRuntimeException();
      serverStreamListener.error(ex);
      throw ex;
    } catch (final Exception ex) {
      handleStreamException(ex, serverStreamListener);
    }
  }

  @Override
  public Runnable acceptPutStatement(
      CommandStatementUpdate commandStatementUpdate,
      CallContext callContext,
      FlightStream flightStream,
      StreamListener<PutResult> streamListener) {
    throw CallStatus.UNIMPLEMENTED.withDescription("Statement not supported.").toRuntimeException();
  }

  @Override
  public Runnable acceptPutPreparedStatementUpdate(
      CommandPreparedStatementUpdate commandPreparedStatementUpdate,
      CallContext callContext,
      FlightStream flightStream,
      StreamListener<PutResult> streamListener) {
    throw CallStatus.UNIMPLEMENTED
        .withDescription("PreparedStatement with parameter binding not supported.")
        .toRuntimeException();
  }

  @Override
  public Runnable acceptPutPreparedStatementQuery(
      CommandPreparedStatementQuery commandPreparedStatementQuery,
      CallContext callContext,
      FlightStream flightStream,
      StreamListener<PutResult> streamListener) {
    return () -> {
      try {
        final UserProtos.PreparedStatementArrow.Builder handle =
            UserProtos.PreparedStatementArrow.newBuilder()
                .mergeFrom(commandPreparedStatementQuery.getPreparedStatementHandle());

        List<UserProtos.PreparedStatementParameterValue> parameterList = new ArrayList<>();
        while (flightStream.next() && flightStream.hasRoot()) {
          final VectorSchemaRoot parameters = flightStream.getRoot();
          parameterList.addAll(DremioFlightPreparedStatementUtils.convertParameters(parameters));
        }
        handle.addAllParameters(parameterList);

        final FlightSql.DoPutPreparedStatementResult result =
            FlightSql.DoPutPreparedStatementResult.newBuilder()
                .setPreparedStatementHandle(handle.build().toByteString())
                .build();

        try (final ArrowBuf buffer = allocator.buffer(result.getSerializedSize())) {
          buffer.writeBytes(result.toByteArray());
          streamListener.onNext(PutResult.metadata(buffer));
          streamListener.onCompleted();
        }
      } catch (InvalidProtocolBufferException e) {
        final FlightRuntimeException ex =
            CallStatus.INVALID_ARGUMENT
                .withCause(e)
                .withDescription(
                    "Invalid PreparedStatementHandle used in acceptPutPreparedStatementQuery.")
                .toRuntimeException();
        streamListener.onError(ex);
        throw ex;
      } catch (RuntimeException e) {
        streamListener.onError(e);
        throw e;
      }
    };
  }

  @Override
  public FlightInfo getFlightInfoSqlInfo(
      CommandGetSqlInfo commandGetSqlInfo,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);
    return new FlightInfo(
        Schemas.GET_SQL_INFO_SCHEMA,
        flightDescriptor,
        Collections.singletonList(
            new FlightEndpoint(new Ticket(Any.pack(commandGetSqlInfo).toByteArray()))),
        -1,
        -1);
  }

  @Override
  public void getStreamSqlInfo(
      CommandGetSqlInfo commandGetSqlInfo,
      CallContext callContext,
      ServerStreamListener serverStreamListener) {
    try {
      final UserSession userSession = getUserSessionData(callContext).getSession();

      final UserProtos.ServerMeta serverMeta =
          flightWorkManager.getServerMeta(callContext::isCancelled, userSession);

      getNewSqlInfoBuilder(serverMeta).send(commandGetSqlInfo.getInfoList(), serverStreamListener);
    } catch (final Exception ex) {
      handleStreamException(ex, serverStreamListener);
    }
  }

  @Override
  public FlightInfo getFlightInfoTypeInfo(
      CommandGetXdbcTypeInfo commandGetXdbcTypeInfo,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);

    final Schema schema = Schemas.GET_TYPE_INFO_SCHEMA;
    return getFlightInfoForFlightSqlCommands(commandGetXdbcTypeInfo, flightDescriptor, schema);
  }

  @Override
  public void getStreamTypeInfo(
      CommandGetXdbcTypeInfo commandGetXdbcTypeInfo,
      CallContext callContext,
      ServerStreamListener serverStreamListener) {
    try {
      getUserSessionData(callContext);

      flightWorkManager.runGetTypeInfo(serverStreamListener, allocator);
    } catch (final Exception ex) {
      handleStreamException(ex, serverStreamListener);
    }
  }

  @Override
  public FlightInfo getFlightInfoCatalogs(
      CommandGetCatalogs commandGetCatalogs,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);
    final Schema catalogsSchema = Schemas.GET_CATALOGS_SCHEMA;
    return getFlightInfoForFlightSqlCommands(commandGetCatalogs, flightDescriptor, catalogsSchema);
  }

  @Override
  public void getStreamCatalogs(
      CallContext callContext, ServerStreamListener serverStreamListener) {
    try (final VectorSchemaRoot vectorSchemaRoot =
        VectorSchemaRoot.create(Schemas.GET_CATALOGS_SCHEMA, allocator)) {
      getUserSessionData(callContext);

      serverStreamListener.start(vectorSchemaRoot);
      vectorSchemaRoot.setRowCount(0);
      serverStreamListener.putNext();
      serverStreamListener.completed();
    } catch (final Exception ex) {
      handleStreamException(ex, serverStreamListener);
    }
  }

  @Override
  public FlightInfo getFlightInfoSchemas(
      CommandGetDbSchemas commandGetSchemas,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);
    final Schema schema = Schemas.GET_SCHEMAS_SCHEMA;
    return getFlightInfoForFlightSqlCommands(commandGetSchemas, flightDescriptor, schema);
  }

  @Override
  public void getStreamSchemas(
      CommandGetDbSchemas commandGetSchemas,
      CallContext callContext,
      ServerStreamListener serverStreamListener) {
    try {
      final UserSession session = getUserSessionData(callContext).getSession();

      String catalog = commandGetSchemas.hasCatalog() ? commandGetSchemas.getCatalog() : null;
      String schemaFilterPattern =
          commandGetSchemas.hasDbSchemaFilterPattern()
              ? commandGetSchemas.getDbSchemaFilterPattern()
              : null;

      flightWorkManager.getSchemas(
          catalog,
          schemaFilterPattern,
          serverStreamListener,
          allocator,
          callContext::isCancelled,
          session);
    } catch (final Exception ex) {
      handleStreamException(ex, serverStreamListener);
    }
  }

  @Override
  public FlightInfo getFlightInfoTables(
      CommandGetTables commandGetTables,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);
    final Schema schema;
    if (commandGetTables.getIncludeSchema()) {
      schema = Schemas.GET_TABLES_SCHEMA;
    } else {
      schema = Schemas.GET_TABLES_SCHEMA_NO_SCHEMA;
    }

    return getFlightInfoForFlightSqlCommands(commandGetTables, flightDescriptor, schema);
  }

  @Override
  public void getStreamTables(
      CommandGetTables commandGetTables,
      CallContext callContext,
      ServerStreamListener serverStreamListener) {
    try {
      final UserSession session = getUserSessionData(callContext).getSession();

      flightWorkManager.runGetTables(
          commandGetTables, serverStreamListener, callContext::isCancelled, allocator, session);
    } catch (final Exception ex) {
      handleStreamException(ex, serverStreamListener);
    }
  }

  @Override
  public FlightInfo getFlightInfoTableTypes(
      CommandGetTableTypes commandGetTableTypes,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);
    final Schema schema = Schemas.GET_TABLE_TYPES_SCHEMA;

    return getFlightInfoForFlightSqlCommands(commandGetTableTypes, flightDescriptor, schema);
  }

  @Override
  public void getStreamTableTypes(
      CallContext callContext, ServerStreamListener serverStreamListener) {
    try {
      getUserSessionData(callContext);

      flightWorkManager.runGetTablesTypes(serverStreamListener, allocator);
    } catch (final Exception ex) {
      handleStreamException(ex, serverStreamListener);
    }
  }

  @Override
  public FlightInfo getFlightInfoPrimaryKeys(
      CommandGetPrimaryKeys commandGetPrimaryKeys,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);
    final Schema schema = Schemas.GET_PRIMARY_KEYS_SCHEMA;
    return new FlightInfo(schema, flightDescriptor, Collections.emptyList(), -1, -1);
  }

  @Override
  public void getStreamPrimaryKeys(
      CommandGetPrimaryKeys commandGetPrimaryKeys,
      CallContext callContext,
      ServerStreamListener serverStreamListener) {
    final FlightRuntimeException ex =
        CallStatus.UNIMPLEMENTED
            .withDescription("CommandGetPrimaryKeys not supported.")
            .toRuntimeException();
    handleStreamException(ex, serverStreamListener);
  }

  @Override
  public FlightInfo getFlightInfoExportedKeys(
      CommandGetExportedKeys commandGetExportedKeys,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);
    final Schema schema = Schemas.GET_EXPORTED_KEYS_SCHEMA;
    return new FlightInfo(schema, flightDescriptor, Collections.emptyList(), -1, -1);
  }

  @Override
  public void getStreamExportedKeys(
      CommandGetExportedKeys commandGetExportedKeys,
      CallContext callContext,
      ServerStreamListener serverStreamListener) {
    final FlightRuntimeException ex =
        CallStatus.UNIMPLEMENTED
            .withDescription("CommandGetExportedKeys not supported.")
            .toRuntimeException();
    handleStreamException(ex, serverStreamListener);
  }

  @Override
  public FlightInfo getFlightInfoImportedKeys(
      CommandGetImportedKeys commandGetImportedKeys,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);
    final Schema schema = Schemas.GET_IMPORTED_KEYS_SCHEMA;
    return new FlightInfo(schema, flightDescriptor, Collections.emptyList(), -1, -1);
  }

  @Override
  public FlightInfo getFlightInfoCrossReference(
      CommandGetCrossReference commandGetCrossReference,
      CallContext callContext,
      FlightDescriptor flightDescriptor) {
    getUserSessionData(callContext);
    final Schema schema = Schemas.GET_CROSS_REFERENCE_SCHEMA;
    return new FlightInfo(schema, flightDescriptor, Collections.emptyList(), -1, -1);
  }

  @Override
  public void getStreamImportedKeys(
      CommandGetImportedKeys commandGetImportedKeys,
      CallContext callContext,
      ServerStreamListener serverStreamListener) {
    final FlightRuntimeException ex =
        CallStatus.UNIMPLEMENTED
            .withDescription("CommandGetImportedKeys not supported.")
            .toRuntimeException();
    handleStreamException(ex, serverStreamListener);
  }

  @Override
  public void getStreamCrossReference(
      CommandGetCrossReference commandGetCrossReference,
      CallContext callContext,
      ServerStreamListener serverStreamListener) {
    final FlightRuntimeException ex =
        CallStatus.UNIMPLEMENTED
            .withDescription("CommandGetCrossReference not supported.")
            .toRuntimeException();
    handleStreamException(ex, serverStreamListener);
  }

  @Override
  public void close() throws Exception {}

  /// Helper method to execute Flight requests with the correct RequestContext based on the supplied
  // CallContext.
  /// This should be called for FlightProducer interface methods (not FlightSqlProducer interface
  // methods which
  /// are just routed through FlightProducer methods). These methods are getFlightInfo(),
  // getSchema(), getStream(),
  /// acceptPut(), listActions(), doAction().
  private <V> V runWithRequestContext(CallContext context, Callable<V> callable) {
    try {
      final UserSessionService.UserSessionData sessionData = getUserSessionData(context);
      RequestContext requestContext = populateUserContextInCurrentRequestContext(sessionData);
      return requestContextDecorator
          .get()
          .apply(requestContext, context, sessionData)
          .call(callable);
    } catch (Exception ex) {
      // Flight request handlers cannot throw any checked exceptions. So propagate RuntimeExceptions
      // and convert
      // checked exceptions to FlightRuntimeExceptions. Most exceptions thrown from above should
      // really be
      // FlightRuntimeExceptions already though.
      if (ex instanceof RuntimeException) {
        throw (RuntimeException) ex;
      } else {
        throw CallStatus.UNKNOWN.withCause(ex).toRuntimeException();
      }
    }
  }

  @VisibleForTesting
  RequestContext populateUserContextInCurrentRequestContext(UserSessionData sessionData) {
    RequestContext requestContext = RequestContext.current();
    if (sessionData.getSession() != null && sessionData.getSession().getCredentials() != null) {
      String userName = sessionData.getSession().getCredentials().getUserName();
      try {
        User user = userServiceProvider.get().getUser(userName);
        UID uid = user.getUID();
        requestContext = requestContext.with(UserContext.CTX_KEY, UserContext.of(uid.getId()));
      } catch (UserNotFoundException e) {
        throw new RuntimeException(e);
      }
    }
    return requestContext;
  }

  private void runPreparedStatement(
      CallContext callContext,
      ServerStreamListener serverStreamListener,
      UserProtos.PreparedStatementHandle preparedStatementHandle) {
    runPreparedStatement(
        callContext, serverStreamListener, preparedStatementHandle, ImmutableList.of());
  }

  private void runPreparedStatement(
      CallContext callContext,
      ServerStreamListener serverStreamListener,
      UserProtos.PreparedStatementHandle preparedStatementHandle,
      List<UserProtos.PreparedStatementParameterValue> preparedStatementParameters) {
    final UserSessionService.UserSessionData sessionData = getUserSessionData(callContext);
    final ChangeTrackingUserSession userSession =
        ChangeTrackingUserSession.Builder.newBuilder()
            .withDelegate(sessionData.getSession())
            .build();

    flightWorkManager.runPreparedStatement(
        preparedStatementHandle,
        preparedStatementParameters,
        serverStreamListener,
        allocator,
        userSession,
        () -> {
          if (userSession.isUpdated()) {
            sessionsManager.updateSession(sessionData);
          }
        });
  }

  private RequestContext getRequestContext(String projectId, String orgId) {
    if (!Strings.isNullOrEmpty(projectId) && !Strings.isNullOrEmpty(orgId)) {
      return RequestContext.current()
          .with(TenantContext.CTX_KEY, new TenantContext(projectId, orgId));
    } else {
      return RequestContext.current();
    }
  }

  /**
   * Helper method to retrieve CallHeaders from the CallContext.
   *
   * @param callContext the CallContext to retrieve headers from.
   * @return CallHeaders retrieved from provided CallContext.
   */
  private CallHeaders retrieveHeadersFromCallContext(CallContext callContext) {
    return callContext
        .getMiddleware(DremioFlightService.FLIGHT_CLIENT_PROPERTIES_MIDDLEWARE_KEY)
        .headers();
  }

  private UserSessionService.UserSessionData getUserSessionData(CallContext callContext) {
    final CallHeaders incomingHeaders = retrieveHeadersFromCallContext(callContext);
    UserSessionService.UserSessionData sessionData =
        sessionsManager.getUserSession(callContext.peerIdentity(), incomingHeaders);

    if (sessionData == null) {
      try {
        sessionData =
            sessionsManager.createUserSession(
                callContext.peerIdentity(), incomingHeaders, Optional.empty());
        sessionsManager.decorateResponse(callContext, sessionData);
      } catch (Exception e) {
        final String errorDescription = "Unable to create user session";
        logger.error(errorDescription, e);
        throw CallStatus.INTERNAL
            .withCause(e)
            .withDescription(errorDescription)
            .toRuntimeException();
      }
    }

    return sessionData;
  }

  private <T extends Message> FlightInfo getFlightInfoForFlightSqlCommands(
      T command, FlightDescriptor flightDescriptor, Schema schema) {
    final Ticket ticket = new Ticket(pack(command).toByteArray());

    final FlightEndpoint flightEndpoint = new FlightEndpoint(ticket);
    return new FlightInfo(schema, flightDescriptor, ImmutableList.of(flightEndpoint), -1, -1);
  }

  private boolean isFlightSqlCommand(Any command) {
    return command.is(CommandStatementQuery.class)
        || command.is(CommandPreparedStatementQuery.class)
        || command.is(CommandGetCatalogs.class)
        || command.is(CommandGetDbSchemas.class)
        || command.is(CommandGetTables.class)
        || command.is(CommandGetTableTypes.class)
        || command.is(CommandGetSqlInfo.class)
        || command.is(CommandGetPrimaryKeys.class)
        || command.is(CommandGetExportedKeys.class)
        || command.is(CommandGetImportedKeys.class)
        || command.is(TicketStatementQuery.class)
        || command.is(CommandGetXdbcTypeInfo.class);
  }

  private boolean isFlightSqlCommand(byte[] bytes) {
    try {
      Any command = Any.parseFrom(bytes);
      return isFlightSqlCommand(command);
    } catch (InvalidProtocolBufferException e) {
      return false;
    }
  }

  private boolean isFlightSqlCommand(FlightDescriptor flightDescriptor) {
    return isFlightSqlCommand(flightDescriptor.getCommand());
  }

  private boolean isFlightSqlTicket(Ticket ticket) {
    // The byte array on ticket is a serialized FlightSqlCommand
    return isFlightSqlCommand(ticket.getBytes());
  }

  private boolean isFlightSqlAction(Action action) {
    String actionType = action.getType();
    return FlightSqlUtils.FLIGHT_SQL_ACTIONS.stream()
        .anyMatch(action2 -> action2.getType().equals(actionType));
  }

  private static void handleStreamException(Exception ex, ServerStreamListener listener) {
    logger.error("Error from Flight streaming function", ex);
    if (ex instanceof UserException) {
      listener.error(DremioFlightErrorMapper.toFlightRuntimeException((UserException) ex));
    } else {
      listener.error(CallStatus.INTERNAL.withCause(ex).toRuntimeException());
    }

    if (ex instanceof RuntimeException) {
      throw (RuntimeException) ex;
    } else {
      throw CallStatus.INTERNAL.withCause(ex).toRuntimeException();
    }
  }
}
