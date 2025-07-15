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
package com.dremio.exec.store.hive;

import static com.dremio.exec.store.hive.BaseHiveStoragePlugin.HIVE_DEFAULT_CTAS_FORMAT;

import com.dremio.common.VM;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.store.hive.exec.FileSystemConfUtil;
import com.google.common.base.Preconditions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.orc.OrcConf;

/** Helper class for constructing HiveConfs from plugin configurations. */
public class HiveConfFactory {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(HiveConfFactory.class);
  private static final String DREMIO_SOURCE_CONFIGURATION_SOURCE = "Dremio source configuration";

  public static final String HIVE_ENABLE_ASYNC = "hive.async.enabled";
  public static final String HIVE_ENABLE_CACHE_FOR_S3_AND_AZURE_STORAGE =
      "hive.cache.enabledForS3AndADLSG2";
  public static final String HIVE_ENABLE_CACHE_FOR_HDFS = "hive.cache.enabledForHDFS";
  public static final String HIVE_ENABLE_CACHE_FOR_GCS = "hive.cache.enabledForGCS";
  public static final String HIVE_MAX_HIVE_CACHE_SPACE = "hive.cache.maxspace";
  // Config is only used in tests and should not be set to true in production.
  public static final String ENABLE_DML_TESTS_WITHOUT_LOCKING = "enable.dml.tests.without.locking";

  // Hadoop properties reference:
  // hadoop/hadoop-common-project/hadoop-common/src/main/resources/core-default.xml

  // S3 Hadoop file system implementation
  private static final String FS_S3_IMPL = "fs.s3.impl";
  private static final String FS_S3N_IMPL = "fs.s3n.impl";
  private static final String FS_S3_IMPL_DEFAULT = "org.apache.hadoop.fs.s3a.S3AFileSystem";

  //  10000, "Max cache size for keeping meta info about orc splits cached in the client.")
  public static final String HIVE_ORC_CACHE_STRIPE_DETAILS_SIZE =
      "hive.orc.cache.stripe.details.size";

  public HiveConf createHiveConf(HiveStoragePluginConfig config) {
    final HiveConf hiveConf = createBaseHiveConf(config);

    switch (config.authType) {
      case STORAGE:
        // populate hiveConf with default authorization values
        break;
      case SQL:
        // Turn on sql-based authorization
        setConf(hiveConf, HiveConf.ConfVars.HIVE_AUTHORIZATION_ENABLED, true);
        setConf(
            hiveConf,
            HiveConf.ConfVars.HIVE_AUTHENTICATOR_MANAGER,
            "org.apache.hadoop.hive.ql.security.ProxyUserAuthenticator");
        setConf(
            hiveConf,
            HiveConf.ConfVars.HIVE_AUTHORIZATION_MANAGER,
            "org.apache.hadoop.hive.ql.security.authorization.plugin.sqlstd.SQLStdHiveAuthorizerFactory");
        setConf(hiveConf, HiveConf.ConfVars.HIVE_SERVER2_ENABLE_DOAS, false);
        break;
      default:
        // Code should not reach here
        throw new UnsupportedOperationException("Unknown authorization type " + config.authType);
    }
    return hiveConf;
  }

  protected HiveConf createBaseHiveConf(BaseHiveStoragePluginConfig<?, ?> config) {
    // Note: HiveConf tries to use the context classloader first, then uses the classloader that it
    // itself
    // is in. If the context classloader is non-null, it will prevent using the PF4J classloader.
    // We do not need synchronization when changing this, since it is per-thread anyway.
    final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(null);
    HiveConf hiveConf;
    try {
      hiveConf = new HiveConf();
    } finally {
      Thread.currentThread().setContextClassLoader(contextLoader);
    }

    final String metastoreURI =
        String.format(
            "thrift://%s:%d",
            Preconditions.checkNotNull(config.hostname, "Hive hostname must be provided."),
            config.port);
    setConf(hiveConf, HiveConf.ConfVars.METASTOREURIS, metastoreURI);

    if (config.enableSasl) {
      setConf(hiveConf, HiveConf.ConfVars.METASTORE_USE_THRIFT_SASL, true);
      if (config.kerberosPrincipal != null) {
        setConf(hiveConf, HiveConf.ConfVars.METASTORE_KERBEROS_PRINCIPAL, config.kerberosPrincipal);
      }
    }
    FileSystemConfUtil.FS_CACHE_DISABLES.forEach(hiveConf::set);
    setConf(hiveConf, HIVE_ENABLE_ASYNC, config.enableAsync);
    setConf(
        hiveConf,
        HIVE_ENABLE_CACHE_FOR_S3_AND_AZURE_STORAGE,
        config.isCachingEnabledForS3AzureAndGCS);
    setConf(hiveConf, HIVE_ENABLE_CACHE_FOR_HDFS, config.isCachingEnabledForHDFS);
    setConf(hiveConf, HIVE_ENABLE_CACHE_FOR_GCS, config.isCachingEnabledForS3AzureAndGCS);
    setConf(hiveConf, HIVE_MAX_HIVE_CACHE_SPACE, config.maxCacheSpacePct);

    setConf(hiveConf, HIVE_DEFAULT_CTAS_FORMAT, config.getDefaultCtasFormat());
    setConf(hiveConf, HiveFsUtils.USE_HIVE_PLUGIN_FS_CACHE, "True");

    FileSystemConfUtil.S3_PROPS.forEach(hiveConf::set);
    addUserProperties(hiveConf, config);
    return hiveConf;
  }

  /**
   * Fills in a HiveConf instance with any user provided configuration parameters
   *
   * @param hiveConf - the conf to fill in
   * @param config - the user provided parameters
   * @return
   */
  protected static void addUserProperties(
      HiveConf hiveConf, BaseHiveStoragePluginConfig<?, ?> config) {
    // Used to capture properties set by user
    final Set<String> userPropertyNames = new HashSet<>();

    addUserPropertyList(config.propertyList, userPropertyNames, hiveConf);
    addUserPropertyList(config.secretPropertyList, userPropertyNames, hiveConf);

    // Check if zero-copy has been set by user
    boolean zeroCopySetByUser =
        userPropertyNames.contains(OrcConf.USE_ZEROCOPY.getAttribute())
            || userPropertyNames.contains(OrcConf.USE_ZEROCOPY.getHiveConfName());
    // Configure zero-copy for ORC reader
    if (!zeroCopySetByUser) {
      if (VM.isWindowsHost() || VM.isMacOSHost()) {
        logger.debug(
            "MacOS or Windows host detected. Not automatically enabling ORC zero-copy feature");
      } else {
        String fs = hiveConf.get(FileSystem.FS_DEFAULT_NAME_KEY);
        // Equivalent to a case-insensitive startsWith...
        if (fs.regionMatches(true, 0, "maprfs", 0, 6)) {
          // DX-12672: do not enable ORC zero-copy on MapRFS
          logger.debug("MapRFS detected. Not automatically enabling ORC zero-copy feature");
        } else {
          logger.debug("Linux host detected. Enabling ORC zero-copy feature");
          setConf(hiveConf, OrcConf.USE_ZEROCOPY.getHiveConfName(), true);
        }
      }
    } else {
      boolean useZeroCopy = OrcConf.USE_ZEROCOPY.getBoolean(hiveConf);
      if (useZeroCopy) {
        logger.warn("ORC zero-copy feature has been manually enabled. This is not recommended.");
      } else {
        logger.warn(
            "ORC zero-copy feature has been manually disabled. This is not recommended and might cause memory issues");
      }
    }

    // Check if ORC Footer cache has been configured by user
    boolean orcStripCacheSetByUser = userPropertyNames.contains(HIVE_ORC_CACHE_STRIPE_DETAILS_SIZE);
    if (orcStripCacheSetByUser) {
      logger.error(
          "ORC stripe details cache has been manually configured. This is not recommended and might cause memory issues");
    } else {
      logger.debug("Disabling ORC stripe details cache.");
      setConf(hiveConf, HIVE_ORC_CACHE_STRIPE_DETAILS_SIZE, 0);
    }

    // Check if fs.s3(n).impl has been set by user
    trySetDefault(userPropertyNames, hiveConf, FS_S3_IMPL, FS_S3_IMPL_DEFAULT);
    trySetDefault(userPropertyNames, hiveConf, FS_S3N_IMPL, FS_S3_IMPL_DEFAULT);

    FileSystemConfUtil.WASB_PROPS.forEach(hiveConf::set);
    FileSystemConfUtil.ABFS_PROPS.forEach(hiveConf::set);
  }

  private static void trySetDefault(
      final Set<String> userPropertyNames,
      HiveConf hiveConf,
      final String confProp,
      final String confPropVal) {
    if (userPropertyNames.contains(confProp)) {
      logger.warn(confProp + " is explicitly set. This is not recommended.");
    } else {
      logger.debug("Setting " + confProp + " to " + confPropVal);
      setConf(hiveConf, confProp, confPropVal);
    }
  }

  protected static void setConf(HiveConf configuration, String name, String value) {
    configuration.set(name, value, DREMIO_SOURCE_CONFIGURATION_SOURCE);
  }

  protected static void setConf(HiveConf configuration, HiveConf.ConfVars confVar, String value) {
    setConf(configuration, confVar.varname, value);
  }

  protected static void setConf(HiveConf configuration, HiveConf.ConfVars confVar, int value) {
    setConf(configuration, confVar.varname, Integer.toString(value));
  }

  protected static void setConf(HiveConf configuration, HiveConf.ConfVars confVar, boolean value) {
    setConf(configuration, confVar.varname, Boolean.toString(value));
  }

  protected static void setConf(HiveConf hiveConf, String intProperty, int intValue) {
    hiveConf.setInt(intProperty, intValue);
  }

  protected static void setConf(HiveConf hiveConf, String propertyName, boolean booleanValue) {
    hiveConf.setBoolean(propertyName, booleanValue);
  }

  private static void checkUnsupportedProps(String name, String value) {
    if ("parquet.column.index.access".equals(name) && Boolean.parseBoolean(value)) {
      throw new IllegalArgumentException("Unsupported Hive config: " + name + '=' + value);
    }
  }

  /**
   * adds the user based Properties to the Hive Config
   *
   * @param properties, either the publicly-viewed propertyList or the masked-UI secretPropertyList
   */
  private static void addUserPropertyList(
      List<Property> properties, Set<String> userPropertyNames, HiveConf hiveConf) {
    if (properties != null) {
      for (Property prop : properties) {
        checkUnsupportedProps(prop.name, prop.value);
        userPropertyNames.add(prop.name);
        setConf(hiveConf, prop.name, prop.value);
        if (logger.isTraceEnabled()) {
          logger.trace("HiveConfig Override {}={}", prop.name, prop.value);
        }
      }
    }
  }
}
