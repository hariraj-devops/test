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

import static com.dremio.service.flight.client.properties.DremioFlightClientProperties.applyMutableClientProperties;

import com.dremio.sabot.rpc.user.SessionOptionValue;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.usersessions.UserSessionService;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import javax.inject.Provider;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractSessionServiceFlightManager implements DremioFlightSessionsManager {
  private static final Logger logger =
      LoggerFactory.getLogger(AbstractSessionServiceFlightManager.class);

  public static final String SESSION_ID_KEY = "arrow_flight_session_id";

  private final UserSessionService userSessionService;

  public AbstractSessionServiceFlightManager(
      Provider<UserSessionService> userSessionServiceProvider) throws Exception {
    Preconditions.checkArgument(
        userSessionServiceProvider != null, "userSessionServiceProvider cannot be null");
    this.userSessionService = userSessionServiceProvider.get();
    Preconditions.checkArgument(
        this.userSessionService != null, "userSessionService cannot be null");
    logger.debug("About to start userSessionService");
    this.userSessionService.start();
  }

  @Override
  public UserSessionService.UserSessionData createUserSession(
      String peerIdentity,
      CallHeaders incomingHeaders,
      Optional<Map<String, SessionOptionValue>> sessionOptionValueMap) {

    final String credential = getUserCredentials(peerIdentity);
    final UserSession userSession = buildUserSession(credential, incomingHeaders);
    applyMutableClientProperties(userSession, incomingHeaders);

    sessionOptionValueMap.ifPresent(
        stringSessionOptionValueMap ->
            stringSessionOptionValueMap.forEach(userSession::setSessionOption));
    try {
      final UserSessionService.SessionIdAndVersion idAndVersion =
          putUserSession(userSession, peerIdentity);

      return new UserSessionService.UserSessionData(
          userSession, idAndVersion.getVersion(), idAndVersion.getId());

    } catch (Exception e) {
      final String errorDescription = "Unable to create user session";
      logger.error(errorDescription, e);
      throw CallStatus.INTERNAL.withCause(e).withDescription(errorDescription).toRuntimeException();
    }
  }

  protected abstract UserSessionService.SessionIdAndVersion putUserSession(
      UserSession userSession, String peerIdentity) throws Exception;

  /**
   * Provides the user credential (username or user_id) for consumption by buildUserSession().
   * buildUserSession() will build UserSession Credentials based on what is provided here.
   *
   * @param peerIdentity a newline separated string comprising org_id and user_id, or a bearer token
   * @return either username or user_id
   */
  protected abstract String getUserCredentials(String peerIdentity);

  /**
   * Build the UserSession object using the UserSession Builder.
   *
   * @param username The username to build UserSession for.
   * @param incomingHeaders The CallHeaders to parse client properties from.
   * @return An instance of UserSession.
   */
  protected abstract UserSession buildUserSession(String username, CallHeaders incomingHeaders);

  @Override
  public UserSessionService.UserSessionData getUserSession(
      String peerIdentity, CallHeaders incomingHeaders) {
    final String sessionId = CookieUtils.getCookieValue(incomingHeaders, SESSION_ID_KEY);
    if (sessionId == null) {
      logger.debug("No sessionId is available in Headers.");
      return null;
    }

    UserSessionService.UserSessionAndVersion userSessionAndVersion;
    try {
      userSessionAndVersion = getUserSessionAndVersion(sessionId, peerIdentity);
    } catch (Exception e) {
      final String errorDescription = "Unable to retrieve user session.";
      logger.error(errorDescription, e);
      throw CallStatus.INTERNAL.withCause(e).withDescription(errorDescription).toRuntimeException();
    }

    if (userSessionAndVersion == null) {
      logger.error("UserSession is not available in SessionManager.");
      throw CallStatus.NOT_FOUND
          .withDescription("UserSession is not available in the cache")
          .toRuntimeException();
    }

    final UserSession userSession = userSessionAndVersion.getSession();

    return new UserSessionService.UserSessionData(
        userSession, userSessionAndVersion.getVersion(), sessionId);
  }

  protected abstract UserSessionService.UserSessionAndVersion getUserSessionAndVersion(
      String sessionId, String peerIdentity) throws Exception;

  /**
   * Provides a copy of the UserSession the retrieved from the UserSessionService decorated with
   * fields that were not able to be populated when it was created.
   *
   * @param userSession the source user session
   * @param sessionId sessionId of the UserSession to be retrieved
   * @param peerIdentity a string comprising org_id and user_id
   * @return rebuilt UserSession decorated with missing fields
   */
  protected abstract UserSession buildRetrievedUserSession(
      UserSession userSession, String sessionId, String peerIdentity);

  @Override
  public void decorateResponse(
      FlightProducer.CallContext callContext, UserSessionService.UserSessionData sessionData) {
    addCookie(callContext, SESSION_ID_KEY, sessionData.getSessionId());
  }

  @Override
  public void updateSession(UserSessionService.UserSessionData updatedSession) {
    userSessionService.updateSession(
        updatedSession.getSessionId(), updatedSession.getVersion(), updatedSession.getSession());
  }

  @Override
  public void closeSession(
      FlightProducer.CallContext callContext, UserSessionService.UserSessionData sessionData) {
    addCookie(
        callContext, SESSION_ID_KEY, sessionData.getSessionId(), ImmutableMap.of("MaxAge", "0"));
  }

  @Override
  public void close() throws Exception {}

  /**
   * Helper method to set the outgoing cookies
   *
   * @param callContext the CallContext to get the middleware from
   * @param key the key of the cookie
   * @param value the value of the cookie
   */
  private void addCookie(FlightProducer.CallContext callContext, String key, String value) {
    callContext
        .getMiddleware(DremioFlightService.FLIGHT_CLIENT_PROPERTIES_MIDDLEWARE_KEY)
        .addCookie(key, value);
  }

  /**
   * Helper method to set the outgoing cookies
   *
   * @param callContext the CallContext to get the middleware from
   * @param key the key of the cookie
   * @param value the value of the cookie
   * @param attributes the additional attributes of the cookie
   */
  private void addCookie(
      FlightProducer.CallContext callContext,
      String key,
      String value,
      Map<String, String> attributes) {
    callContext
        .getMiddleware(DremioFlightService.FLIGHT_CLIENT_PROPERTIES_MIDDLEWARE_KEY)
        .addCookie(key, value, attributes);
  }

  protected UserSessionService getUserSessionService() {
    return this.userSessionService;
  }
}
