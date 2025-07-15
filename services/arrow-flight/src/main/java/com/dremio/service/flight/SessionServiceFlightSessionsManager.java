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

import static com.dremio.service.flight.client.properties.DremioFlightClientProperties.applyClientPropertiesToUserSessionBuilder;

import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserProtos;
import com.dremio.exec.server.options.SessionOptionManager;
import com.dremio.exec.server.options.SessionOptionManagerFactory;
import com.dremio.exec.server.options.SessionOptionManagerFactoryImpl;
import com.dremio.exec.server.options.SessionOptionManagerImpl;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValidatorListing;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.flightcommon.CallContextUtil;
import com.dremio.service.tokens.TokenDetails;
import com.dremio.service.tokens.TokenManager;
import com.dremio.service.usersessions.UserSessionService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import javax.inject.Provider;
import org.apache.arrow.flight.CallHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Manages UserSession creation and UserSession cache. */
public class SessionServiceFlightSessionsManager extends AbstractSessionServiceFlightManager {
  private static final Logger logger =
      LoggerFactory.getLogger(SessionServiceFlightSessionsManager.class);

  private final Provider<TokenManager> tokenManagerProvider;
  private final SessionOptionManagerFactory sessionOptionManagerFactory;
  private final OptionManager optionManager;
  private final OptionValidatorListing optionValidatorListing;

  public SessionServiceFlightSessionsManager(
      OptionValidatorListing optionValidatorListing,
      OptionManager optionManager,
      Provider<TokenManager> tokenManagerProvider,
      Provider<UserSessionService> userSessionServiceProvider)
      throws Exception {
    super(userSessionServiceProvider);
    Preconditions.checkArgument(optionManager != null, "optionManager cannot be null");
    Preconditions.checkArgument(
        optionValidatorListing != null, "optionValidatorListing cannot be null");
    Preconditions.checkArgument(
        tokenManagerProvider != null, "tokenManagerProvider cannot be null");
    Preconditions.checkArgument(
        tokenManagerProvider.get() != null, "tokenManagerProvider cannot be null");

    this.tokenManagerProvider = tokenManagerProvider;
    this.optionManager = optionManager;
    this.optionValidatorListing = optionValidatorListing;
    this.sessionOptionManagerFactory = new SessionOptionManagerFactoryImpl(optionValidatorListing);
  }

  @Override
  protected UserSessionService.SessionIdAndVersion putUserSession(
      UserSession userSession, String peerIdentity) throws Exception {
    return getUserSessionService().putSession(userSession);
  }

  @Override
  protected String getUserCredentials(String peerIdentity) {
    final CallContextUtil callContext = CallContextUtil.newCallContextUtil(peerIdentity);
    if (callContext.isCompositePeerIdentity()) {
      return callContext.getUserId().get();
    } else {
      final TokenDetails tokenDetails = tokenManagerProvider.get().validateToken(peerIdentity);
      return tokenDetails.username;
    }
  }

  @VisibleForTesting
  @Override
  protected UserSession buildUserSession(String username, CallHeaders incomingHeaders) {
    final UserSession.Builder builder =
        UserSession.Builder.newBuilder()
            .withSessionOptionManager(
                new SessionOptionManagerImpl(optionValidatorListing), optionManager)
            .withCredentials(
                UserBitShared.UserCredentials.newBuilder().setUserName(username).build())
            .withUserProperties(UserProtos.UserProperties.getDefaultInstance())
            .setSupportComplexTypes(true)
            .withClientInfos(
                UserBitShared.RpcEndpointInfos.newBuilder().setName("Arrow Flight").build());

    if (incomingHeaders != null) {
      applyClientPropertiesToUserSessionBuilder(builder, incomingHeaders);
    }

    return builder.build();
  }

  @Override
  protected UserSessionService.UserSessionAndVersion getUserSessionAndVersion(
      String sessionId, String peerIdentity) throws Exception {
    final UserSessionService.UserSessionAndVersion userSessionAndVersion =
        getUserSessionService().getSession(sessionId);
    if (userSessionAndVersion == null) {
      return null;
    }

    final UserSession userSession =
        buildRetrievedUserSession(userSessionAndVersion.getSession(), sessionId, peerIdentity);
    return new UserSessionService.UserSessionAndVersion(
        userSession, userSessionAndVersion.getVersion());
  }

  @Override
  protected UserSession buildRetrievedUserSession(
      UserSession userSession, String sessionId, String peerIdentity) {
    assert this.sessionOptionManagerFactory != null;
    final SessionOptionManager sessionOptionManager =
        this.sessionOptionManagerFactory.getOrCreate(sessionId);

    final UserSession.Builder builder =
        UserSession.Builder.newBuilder(userSession)
            .withSessionOptionManager(sessionOptionManager, this.optionManager);

    final UserBitShared.UserCredentials userCredentials = userSession.getCredentials();
    if (userCredentials != null
        && Strings.isNullOrEmpty(userCredentials.getUserName())
        && !Strings.isNullOrEmpty(userCredentials.getUserId())) {
      final String credential = getUserCredentials(peerIdentity);
      builder.withCredentials(
          UserBitShared.UserCredentials.newBuilder()
              .setUserName(credential)
              .setUserId(userCredentials.getUserId())
              .build());
      logger.debug(
          "Rebuilding UserSession {} credentials from UserId {}",
          sessionId,
          userCredentials.getUserId());
    }

    return builder.build();
  }
}
