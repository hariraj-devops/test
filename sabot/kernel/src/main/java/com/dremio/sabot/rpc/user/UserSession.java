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
package com.dremio.sabot.rpc.user;

import com.dremio.catalog.model.VersionContext;
import com.dremio.common.map.CaseInsensitiveMap;
import com.dremio.common.utils.SqlUtils;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.proto.UserBitShared.QueryId;
import com.dremio.exec.proto.UserBitShared.RpcEndpointInfos;
import com.dremio.exec.proto.UserBitShared.UserCredentials;
import com.dremio.exec.proto.UserProtos.Property;
import com.dremio.exec.proto.UserProtos.RecordBatchFormat;
import com.dremio.exec.proto.UserProtos.UserProperties;
import com.dremio.exec.server.options.SessionOptionManager;
import com.dremio.exec.store.ischema.InfoSchemaConstants;
import com.dremio.exec.work.user.SubstitutionSettings;
import com.dremio.options.OptionManager;
import com.dremio.options.Options;
import com.dremio.options.TypeValidators.BooleanValidator;
import com.dremio.options.TypeValidators.RangeLongValidator;
import com.dremio.options.impl.OptionManagerWrapper;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.calcite.avatica.util.Quoting;

@Options
public class UserSession {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(UserSession.class);

  public static final String SCHEMA = PropertySetter.SCHEMA.toPropertyName();
  public static final String USER = PropertySetter.USER.toPropertyName();
  public static final String PASSWORD = PropertySetter.PASSWORD.toPropertyName();
  public static final String CHECK_METADATA_VALIDITY =
      PropertySetter.CHECK_METADATA_VALIDITY.toPropertyName();
  public static final String NEVER_PROMOTE = PropertySetter.NEVER_PROMOTE.toPropertyName();
  public static final String ERROR_ON_UNSPECIFIED_VERSION =
      PropertySetter.ERROR_ON_UNSPECIFIED_VERSION.toPropertyName();
  public static final String IMPERSONATION_TARGET =
      PropertySetter.IMPERSONATION_TARGET.toPropertyName();
  public static final String QUOTING = PropertySetter.QUOTING.toPropertyName();
  public static final String SUPPORTFULLYQUALIFIEDPROJECTS =
      PropertySetter.SUPPORTFULLYQUALIFIEDPROJECTS.toPropertyName();
  public static final String ROUTING_TAG = PropertySetter.ROUTING_TAG.toPropertyName();
  public static final String ROUTING_QUEUE = PropertySetter.ROUTING_QUEUE.toPropertyName();
  public static final String ROUTING_ENGINE = PropertySetter.ROUTING_ENGINE.toPropertyName();
  public static final String TRACING_ENABLED = PropertySetter.TRACING_ENABLED.toPropertyName();
  public static final String QUERY_LABEL = PropertySetter.QUERY_LABEL.toPropertyName();

  public static final BooleanValidator ENABLE_SESSION_IDS =
      new BooleanValidator("user.session.enable_session_id", true);
  public static final RangeLongValidator MAX_METADATA_COUNT =
      new RangeLongValidator("client.max_metadata_count", 0, Integer.MAX_VALUE, 0);

  private enum PropertySetter {
    USER,
    PASSWORD,

    MAXMETADATACOUNT {
      @Override
      public void setValue(UserSession session, String value) {
        final int maxMetadataCount = Integer.parseInt(value);
        Preconditions.checkArgument(maxMetadataCount >= 0, "MaxMetadataCount must be non-negative");
        session.maxMetadataCount = maxMetadataCount;
      }
    },

    QUOTING {
      @Override
      public void setValue(UserSession session, String value) {
        if (value == null) {
          return;
        }
        final Quoting quoting;
        switch (value.toUpperCase(Locale.ROOT)) {
          case "BACK_TICK":
            quoting = Quoting.BACK_TICK;
            break;

          case "DOUBLE_QUOTE":
            quoting = Quoting.DOUBLE_QUOTE;
            break;

          case "BRACKET":
            quoting = Quoting.BRACKET;
            break;

          default:
            logger.warn("Ignoring message to use initial quoting of type {}.", value);
            return;
        }
        session.initialQuoting = quoting;
      }
    },

    SCHEMA {
      @Override
      public void setValue(UserSession session, String value) {
        session.defaultSchemaPath =
            Strings.isNullOrEmpty(value) ? null : new NamespaceKey(SqlUtils.parseSchemaPath(value));
      }
    },

    CHECK_METADATA_VALIDITY {
      @Override
      public void setValue(UserSession session, String value) {
        session.checkMetadataValidity = "true".equalsIgnoreCase(value);
      }
    },

    NEVER_PROMOTE {
      @Override
      public void setValue(UserSession session, String value) {
        session.neverPromote = "true".equalsIgnoreCase(value);
      }
    },

    ERROR_ON_UNSPECIFIED_VERSION {
      @Override
      public void setValue(UserSession session, String value) {
        session.errorOnUnspecifiedVersion = "true".equalsIgnoreCase(value);
      }
    },

    IMPERSONATION_TARGET {
      @Override
      public void setValue(UserSession session, String value) {
        session.impersonationTarget = value;
      }
    },

    SUPPORTFULLYQUALIFIEDPROJECTS {
      @Override
      public void setValue(UserSession session, String value) {
        session.supportFullyQualifiedProjections = "true".equalsIgnoreCase(value);
      }
    },

    ROUTING_TAG {
      @Override
      public void setValue(UserSession session, String value) {
        session.routingTag = value;
      }
    },

    QUERY_LABEL {
      @Override
      public void setValue(UserSession session, String value) {
        session.queryLabel = value;
      }
    },

    ROUTING_QUEUE {
      @Override
      public void setValue(UserSession session, String value) {
        session.routingQueue = value;
      }
    },

    TRACING_ENABLED {
      @Override
      public void setValue(UserSession session, String value) {
        session.tracingEnabled = "true".equalsIgnoreCase(value);
      }
    },

    ROUTING_ENGINE {
      @Override
      public void setValue(UserSession session, String value) {
        session.routingEngine = value;
      }
    },

    ENGINE {
      @Override
      public void setValue(UserSession session, String value) {
        session.routingEngine = value;
      }
    };

    /**
     * Set the corresponding
     *
     * @param session
     * @param value
     */
    public void setValue(UserSession session, String value) {
      // Default: do nothing
    }

    public String toPropertyName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private volatile QueryId lastQueryId = null;
  private boolean supportComplexTypes = true;
  private UserCredentials credentials;
  private NamespaceKey defaultSchemaPath;
  private SessionOptionManager sessionOptionManager;
  private OptionManager optionManager;

  private RpcEndpointInfos clientInfos;
  private boolean useLegacyCatalogName = false;
  private String impersonationTarget = null;
  private Quoting initialQuoting;
  private boolean supportFullyQualifiedProjections;
  private String routingTag;
  private String queryLabel;
  private String routingQueue;
  private String routingEngine;
  private RecordBatchFormat recordBatchFormat = RecordBatchFormat.DREMIO_23_0;
  private boolean exposeInternalSources = false;
  private boolean tracingEnabled = false;
  private SubstitutionSettings substitutionSettings = SubstitutionSettings.of();
  private int maxMetadataCount = 0;
  private boolean checkMetadataValidity = true;
  private boolean neverPromote = false;
  private boolean errorOnUnspecifiedVersion = false;
  private final CaseInsensitiveMap<VersionContext> sourceVersionMapping =
      CaseInsensitiveMap.newConcurrentMap();
  private final CaseInsensitiveMap<SessionOptionValue> sessionOptionsMap =
      CaseInsensitiveMap.newConcurrentMap();

  public static class Builder {
    private UserSession userSession;

    public static Builder newBuilder() {
      return new Builder();
    }

    /**
     * This newBuilder will assign the caller's session without making a copy !!Note!! that this
     * newBuilder could make modifications to the caller's session settings.
     */
    public static Builder newBuilder(UserSession session) {
      return new Builder(session, false);
    }

    /**
     * This newBuilderWithCopy will make a fresh copy using the copy constructor so the caller's
     * session remains unmodified
     */
    public static Builder newBuilderWithCopy(UserSession session) {
      return new Builder(session, true);
    }

    public Builder withSessionOptionManager(
        SessionOptionManager sessionOptionManager, OptionManager fallback) {
      userSession.sessionOptionManager = sessionOptionManager;
      userSession.optionManager =
          OptionManagerWrapper.Builder.newBuilder()
              .withOptionManager(fallback)
              .withOptionManager(sessionOptionManager)
              .build();
      userSession.maxMetadataCount = (int) userSession.optionManager.getOption(MAX_METADATA_COUNT);
      return this;
    }

    public Builder withCredentials(UserCredentials credentials) {
      userSession.credentials = credentials;
      return this;
    }

    public Builder withSourceVersionMapping(Map<String, VersionContext> sourceVersionMapping) {
      if (sourceVersionMapping != null) {
        userSession.sourceVersionMapping.clear();
        userSession.sourceVersionMapping.putAll(sourceVersionMapping);
      }
      return this;
    }

    public Builder withDefaultSchema(List<String> defaultSchemaPath) {
      if (defaultSchemaPath == null) {
        userSession.defaultSchemaPath = null;
        return this;
      }

      userSession.defaultSchemaPath = new NamespaceKey(defaultSchemaPath);
      return this;
    }

    public Builder withCheckMetadataValidity(boolean value) {
      userSession.checkMetadataValidity = value;
      return this;
    }

    public Builder withNeverPromote(boolean value) {
      userSession.neverPromote = value;
      return this;
    }

    public Builder withErrorOnUnspecifiedVersion(boolean value) {
      userSession.errorOnUnspecifiedVersion = value;
      return this;
    }

    public Builder withClientInfos(RpcEndpointInfos infos) {
      userSession.clientInfos = infos;
      return this;
    }

    public Builder withRecordBatchFormat(RecordBatchFormat recordBatchFormat) {
      userSession.recordBatchFormat = recordBatchFormat;
      return this;
    }

    public Builder withLegacyCatalog() {
      userSession.useLegacyCatalogName = true;
      return this;
    }

    public Builder withInitialQuoting(Quoting quoting) {
      userSession.initialQuoting = quoting;
      return this;
    }

    public Builder withFullyQualifiedProjectsSupport(boolean value) {
      userSession.supportFullyQualifiedProjections = value;
      return this;
    }

    public Builder withSubstitutionSettings(final SubstitutionSettings substitutionSettings) {
      userSession.substitutionSettings = substitutionSettings;
      return this;
    }

    public Builder withUserProperties(UserProperties properties) {
      if (properties == null) {
        return this;
      }

      for (int i = 0; i < properties.getPropertiesCount(); i++) {
        final Property property = properties.getProperties(i);
        final String propertyName = property.getKey().toUpperCase(Locale.ROOT);
        final String propertyValue = property.getValue();
        try {
          final PropertySetter sessionProperty = PropertySetter.valueOf(propertyName);
          sessionProperty.setValue(userSession, propertyValue);
        } catch (IllegalArgumentException e) {
          logger.warn("Ignoring unknown property: {}", propertyName);
        }
      }

      return this;
    }

    public Builder withEngineName(String engineName) {
      if (Strings.isNullOrEmpty(engineName)) {
        return this;
      }
      userSession.routingEngine = engineName;
      return this;
    }

    public Builder setSupportComplexTypes(boolean supportComplexTypes) {
      userSession.supportComplexTypes = supportComplexTypes;
      return this;
    }

    public Builder exposeInternalSources(boolean exposeInternalSources) {
      userSession.exposeInternalSources = exposeInternalSources;
      return this;
    }

    public Builder withSessionOptions(Map<String, SessionOptionValue> sessionOptions) {
      if (sessionOptions != null) {
        userSession.sessionOptionsMap.clear();
        userSession.sessionOptionsMap.putAll(sessionOptions);
      }
      return this;
    }

    public UserSession build() {
      UserSession session = userSession;
      userSession = null;
      return session;
    }

    Builder() {
      userSession = new UserSession();
    }

    Builder(UserSession session, boolean newInstance) {
      if (newInstance) {
        userSession = new UserSession(session);
      } else {
        // Note : This could potentially modify the passed in session since it does not make a new
        // copy.
        userSession = session;
      }
    }
  }

  protected UserSession() {}

  protected UserSession(UserSession userSession) {
    this.lastQueryId = userSession.lastQueryId;
    this.supportComplexTypes = userSession.supportComplexTypes;
    this.credentials = userSession.credentials;
    this.defaultSchemaPath = userSession.defaultSchemaPath;
    this.sessionOptionManager = userSession.sessionOptionManager;
    this.optionManager = userSession.optionManager;
    this.clientInfos = userSession.clientInfos;
    this.useLegacyCatalogName = userSession.useLegacyCatalogName;
    this.impersonationTarget = userSession.impersonationTarget;
    this.initialQuoting = userSession.initialQuoting;
    this.supportFullyQualifiedProjections = userSession.supportFullyQualifiedProjections;
    this.routingTag = userSession.routingTag;
    this.queryLabel = userSession.queryLabel;
    this.routingQueue = userSession.routingQueue;
    this.routingEngine = userSession.routingEngine;
    this.recordBatchFormat = userSession.recordBatchFormat;
    this.exposeInternalSources = userSession.exposeInternalSources;
    this.tracingEnabled = userSession.tracingEnabled;
    this.substitutionSettings = new SubstitutionSettings(userSession.substitutionSettings);
    this.maxMetadataCount = userSession.maxMetadataCount;
    this.checkMetadataValidity = userSession.checkMetadataValidity;
    this.neverPromote = userSession.neverPromote;
    this.errorOnUnspecifiedVersion = userSession.errorOnUnspecifiedVersion;
    this.sourceVersionMapping.putAll(userSession.sourceVersionMapping);
    this.sessionOptionsMap.putAll(userSession.sessionOptionsMap);
  }

  public boolean isSupportComplexTypes() {
    return supportComplexTypes;
  }

  public OptionManager getOptions() {
    return optionManager;
  }

  public void setSessionOptionManager(
      SessionOptionManager sessionOptionManager, OptionManager fallback) {
    this.sessionOptionManager = sessionOptionManager;
    this.optionManager =
        OptionManagerWrapper.Builder.newBuilder()
            .withOptionManager(fallback)
            .withOptionManager(sessionOptionManager)
            .build();
    this.maxMetadataCount = (int) this.optionManager.getOption(MAX_METADATA_COUNT);
  }

  public SessionOptionManager getSessionOptionManager() {
    return sessionOptionManager;
  }

  public String getRoutingTag() {
    return routingTag;
  }

  public String getQueryLabel() {
    return queryLabel;
  }

  public void setQueryLabel(String queryLabel) {
    this.queryLabel = queryLabel;
  }

  public String getRoutingQueue() {
    return routingQueue;
  }

  public void setRoutingQueue(String queueName) {
    this.routingQueue = queueName;
  }

  public String getRoutingEngine() {
    return routingEngine;
  }

  public void setRoutingEngine(String routingEngine) {
    this.routingEngine = routingEngine;
  }

  public String getEngine() {
    return routingEngine;
  }

  public void setEngine(String routingEngine) {
    this.routingEngine = routingEngine;
  }

  public UserCredentials getCredentials() {
    return credentials;
  }

  public RpcEndpointInfos getClientInfos() {
    return clientInfos;
  }

  public RecordBatchFormat getRecordBatchFormat() {
    return recordBatchFormat;
  }

  public boolean exposeInternalSources() {
    return exposeInternalSources;
  }

  public boolean isTracingEnabled() {
    return tracingEnabled;
  }

  public SubstitutionSettings getSubstitutionSettings() {
    return substitutionSettings;
  }

  public String getCatalogName() {
    return useLegacyCatalogName
        ? InfoSchemaConstants.IS_LEGACY_CATALOG_NAME
        : InfoSchemaConstants.IS_CATALOG_NAME;
  }

  public boolean useLegacyCatalogName() {
    return useLegacyCatalogName;
  }

  public int getMaxMetadataCount() {
    return maxMetadataCount;
  }

  /**
   * Does the client requires support for fully qualified column names in projections?
   *
   * <p>Ex: SELECT "elastic.yelp".business.city, "elastic.yelp".business.stars FROM
   * "elastic.yelp".business
   *
   * <p>Note: enabling this option disables complex field references in query (ex. mapCol.mapField,
   * listCol[2])
   *
   * @return
   */
  public boolean supportFullyQualifiedProjections() {
    return supportFullyQualifiedProjections;
  }

  public static String getCatalogName(OptionManager options) {
    return options.getOption(ExecConstants.USE_LEGACY_CATALOG_NAME)
        ? InfoSchemaConstants.IS_LEGACY_CATALOG_NAME
        : InfoSchemaConstants.IS_CATALOG_NAME;
  }

  /**
   * Replace current user credentials with the given user's credentials. Meant to be called only by
   * a {@link InboundImpersonationManager impersonation manager}.
   *
   * @param impersonationManager impersonation manager making this call
   * @param newCredentials user credentials to change to
   */
  public void replaceUserCredentials(
      final InboundImpersonationManager impersonationManager,
      final UserCredentials newCredentials) {
    Preconditions.checkNotNull(
        impersonationManager,
        "User credentials can only be replaced by an" + " impersonation manager.");
    credentials = newCredentials;
  }

  public String getTargetUserName() {
    return impersonationTarget;
  }

  public String getDefaultSchemaName() {
    return defaultSchemaPath == null ? "" : defaultSchemaPath.toString();
  }

  public void incrementQueryCount() {
    sessionOptionManager.incrementQueryCount();
  }

  public Quoting getInitialQuoting() {
    return initialQuoting;
  }

  /**
   * Set the schema path for the session.
   *
   * @param newDefaultSchemaPath New default schema path to set. It should be an absolute schema
   */
  public void setDefaultSchemaPath(List<String> newDefaultSchemaPath) {
    this.defaultSchemaPath =
        newDefaultSchemaPath != null ? new NamespaceKey(newDefaultSchemaPath) : null;
  }

  /**
   * @return Get current default schema path.
   */
  public NamespaceKey getDefaultSchemaPath() {
    return defaultSchemaPath;
  }

  public QueryId getLastQueryId() {
    return lastQueryId;
  }

  public void setLastQueryId(QueryId id) {
    lastQueryId = id;
  }

  public VersionContext getSessionVersionForSource(String sourceName) {
    if (sourceName == null) {
      return VersionContext.NOT_SPECIFIED;
    }
    return sourceVersionMapping.getOrDefault(sourceName, VersionContext.NOT_SPECIFIED);
  }

  public CaseInsensitiveMap<VersionContext> getSourceVersionMapping() {
    return CaseInsensitiveMap.newImmutableMap(sourceVersionMapping);
  }

  public void setSessionVersionForSource(String sourceName, VersionContext versionContext) {
    sourceVersionMapping.put(sourceName, versionContext);
  }

  public boolean checkMetadataValidity() {
    return checkMetadataValidity;
  }

  public boolean neverPromote() {
    return neverPromote;
  }

  public boolean errorOnUnspecifiedVersion() {
    return errorOnUnspecifiedVersion;
  }

  void setErrorOnUnspecifiedVersion(boolean value) {
    errorOnUnspecifiedVersion = value;
  }

  public CaseInsensitiveMap<SessionOptionValue> getSessionOptionsMap() {
    return CaseInsensitiveMap.newImmutableMap(sessionOptionsMap);
  }

  public void setSessionOption(String key, SessionOptionValue value) {
    sessionOptionsMap.put(key, value);
  }
}
