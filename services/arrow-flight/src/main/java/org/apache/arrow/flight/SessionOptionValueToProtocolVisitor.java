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
package org.apache.arrow.flight;

import java.util.Arrays;
import org.apache.arrow.flight.impl.Flight;

public class SessionOptionValueToProtocolVisitor implements SessionOptionValueVisitor<Void> {

  private final Flight.SessionOptionValue.Builder b;

  public static Flight.SessionOptionValue toProtocol(SessionOptionValue sessionOptionValue) {
    final SessionOptionValueToProtocolVisitor visitor = new SessionOptionValueToProtocolVisitor();
    sessionOptionValue.acceptVisitor(visitor);
    return visitor.buildSessionOptionValue();
  }

  // Initialise instance variables
  {
    b = Flight.SessionOptionValue.newBuilder();
  }

  Flight.SessionOptionValue buildSessionOptionValue() {
    return b.build();
  }

  @Override
  public Void visit(String value) {
    b.setStringValue(value);
    return null;
  }

  @Override
  public Void visit(boolean value) {
    b.setBoolValue(value);
    return null;
  }

  @Override
  public Void visit(long value) {
    b.setInt64Value(value);
    return null;
  }

  @Override
  public Void visit(double value) {
    b.setDoubleValue(value);
    return null;
  }

  @Override
  public Void visit(String[] value) {
    Flight.SessionOptionValue.StringListValue.Builder pbSLVBuilder =
        Flight.SessionOptionValue.StringListValue.newBuilder();
    pbSLVBuilder.addAllValues(Arrays.asList(value));
    b.setStringListValue(pbSLVBuilder.build());
    return null;
  }

  @Override
  public Void visit(Void ignored) {
    b.clearOptionValue();
    return null;
  }
}
