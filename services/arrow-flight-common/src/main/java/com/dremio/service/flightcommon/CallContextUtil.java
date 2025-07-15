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
package com.dremio.service.flightcommon;

import java.util.Optional;
import org.apache.arrow.flight.FlightProducer.CallContext;

/*
 * Utilities relating to Flight Context.
 * A peer identity will comprise either a single bearer token, or a newline separated list of
 * <user id>\n
 * <org id>\n
 */
public final class CallContextUtil {

  private static final String SEPARATOR = "\n";
  private static final int ORG_ID_POS = 0;
  private static final int USER_ID_POS = 1;
  private static final int COMPOSITE_LENGTH = 2;

  private final String peerIdentity;

  public static CallContextUtil newCallContextUtil(CallContext callContext) {
    return new CallContextUtil(callContext);
  }

  public static CallContextUtil newCallContextUtil(String peerIdentity) {
    return new CallContextUtil(peerIdentity);
  }

  private CallContextUtil(CallContext callContext) {
    this.peerIdentity = callContext.peerIdentity();
  }

  private CallContextUtil(String peerIdentity) {
    this.peerIdentity = peerIdentity;
  }

  private String[] splitPeerIdentity() {
    return peerIdentity.split(SEPARATOR);
  }

  public boolean isCompositePeerIdentity() {
    return (splitPeerIdentity().length >= COMPOSITE_LENGTH);
  }

  public String getPeerIdentity() {
    return peerIdentity;
  }

  public Optional<String> getOrgId() {
    return isCompositePeerIdentity()
        ? Optional.of(splitPeerIdentity()[ORG_ID_POS])
        : Optional.empty();
  }

  public Optional<String> getUserId() {
    return isCompositePeerIdentity()
        ? Optional.of(splitPeerIdentity()[USER_ID_POS])
        : Optional.empty();
  }

  /* The composite peer identity will be a string that encodes the org ID and user ID.
   * Some peer identities have 3 fields, therefore provide a newline after the second field.
   * It uses the following format:
   * {orgId}
   * {userId}
   * {empty}
   *
   * Newlines are used as separators to avoid conflicts
   */
  public static String peerIdentity(String orgId, String userId) {
    return orgId.concat(SEPARATOR).concat(userId).concat(SEPARATOR);
  }
}
