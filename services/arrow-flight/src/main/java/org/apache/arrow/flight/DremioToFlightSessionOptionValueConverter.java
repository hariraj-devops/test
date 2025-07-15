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

import com.dremio.sabot.rpc.user.SessionOptionValue;
import java.util.HashMap;
import java.util.Map;

public class DremioToFlightSessionOptionValueConverter {
  private static final class DremioToFlightVisitor
      implements SessionOptionValueVisitor<org.apache.arrow.flight.SessionOptionValue> {
    @Override
    public org.apache.arrow.flight.SessionOptionValue visit(String value) {
      return new org.apache.arrow.flight.SessionOptionValue() {
        @Override
        public <T> T acceptVisitor(SessionOptionValueVisitor<T> visitor) {
          return visitor.visit(value);
        }
      };
    }

    @Override
    public org.apache.arrow.flight.SessionOptionValue visit(boolean value) {
      return new org.apache.arrow.flight.SessionOptionValue() {
        @Override
        public <T> T acceptVisitor(SessionOptionValueVisitor<T> visitor) {
          return visitor.visit(value);
        }
      };
    }

    @Override
    public org.apache.arrow.flight.SessionOptionValue visit(long value) {
      return new org.apache.arrow.flight.SessionOptionValue() {
        @Override
        public <T> T acceptVisitor(SessionOptionValueVisitor<T> visitor) {
          return visitor.visit(value);
        }
      };
    }

    @Override
    public org.apache.arrow.flight.SessionOptionValue visit(double value) {
      return new org.apache.arrow.flight.SessionOptionValue() {
        @Override
        public <T> T acceptVisitor(SessionOptionValueVisitor<T> visitor) {
          return visitor.visit(value);
        }
      };
    }

    @Override
    public org.apache.arrow.flight.SessionOptionValue visit(String[] value) {
      return new org.apache.arrow.flight.SessionOptionValue() {
        @Override
        public <T> T acceptVisitor(SessionOptionValueVisitor<T> visitor) {
          return visitor.visit(value);
        }
      };
    }

    @Override
    public org.apache.arrow.flight.SessionOptionValue visit(Void value) {
      return new org.apache.arrow.flight.SessionOptionValue() {
        @Override
        public <T> T acceptVisitor(SessionOptionValueVisitor<T> visitor) {
          return visitor.visit(value);
        }

        @Override
        public boolean isEmpty() {
          return true;
        }
      };
    }
  }

  private static final DremioToFlightVisitor VISITOR = new DremioToFlightVisitor();

  public static org.apache.arrow.flight.SessionOptionValue convert(SessionOptionValue dremioValue) {
    if (dremioValue == null) {
      throw new IllegalArgumentException("SessionOptionValue cannot be null");
    }
    if (dremioValue.hasStringValue()) {
      return VISITOR.visit(dremioValue.getStringValue());
    } else if (dremioValue.hasBoolValue()) {
      return VISITOR.visit(dremioValue.getBoolValue());
    } else if (dremioValue.hasInt64Value()) {
      return VISITOR.visit(dremioValue.getInt64Value());
    } else if (dremioValue.hasDoubleValue()) {
      return VISITOR.visit(dremioValue.getDoubleValue());
    } else if (dremioValue.hasStringListValue()) {
      return VISITOR.visit(dremioValue.getStringListValue().toArray(new String[0]));
    } else {
      return VISITOR.visit((Void) null);
    }
  }

  public static Map<String, org.apache.arrow.flight.SessionOptionValue> convert(
      Map<String, SessionOptionValue> dremioOptions) {
    if (dremioOptions == null) {
      throw new IllegalArgumentException("dremioOptions map cannot be null");
    }
    Map<String, org.apache.arrow.flight.SessionOptionValue> result = new HashMap<>();
    for (Map.Entry<String, SessionOptionValue> entry : dremioOptions.entrySet()) {
      result.put(entry.getKey(), convert(entry.getValue()));
    }
    return result;
  }
}
