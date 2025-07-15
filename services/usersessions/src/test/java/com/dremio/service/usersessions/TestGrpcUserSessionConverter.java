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
package com.dremio.service.usersessions;

import com.dremio.catalog.model.VersionContext;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserBitShared.UserCredentials;
import com.dremio.exec.proto.UserProtos;
import com.dremio.exec.proto.UserProtos.RecordBatchFormat;
import com.dremio.exec.proto.UserProtos.UserProperties;
import com.dremio.exec.work.user.SubstitutionSettings;
import com.dremio.sabot.rpc.user.SessionOptionValue;
import com.dremio.sabot.rpc.user.UserSession;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.calcite.avatica.util.Quoting;
import org.junit.Assert;
import org.junit.Test;

public class TestGrpcUserSessionConverter {
  @Test
  public void testTwoWayConversion() {
    // SesionOptions
    final Map<String, SessionOptionValue> optionsMap = new HashMap<>();
    optionsMap.put(
        "StringListValue",
        SessionOptionValue.Builder.newBuilder()
            .setStringListValue(Arrays.asList("ak", "av"))
            .build());
    optionsMap.put(
        "StringValued", SessionOptionValue.Builder.newBuilder().setStringValue("a string").build());
    optionsMap.put(
        "DoubleValue", SessionOptionValue.Builder.newBuilder().setDoubleValue(8D).build());
    optionsMap.put("BoolValue", SessionOptionValue.Builder.newBuilder().setBoolValue(true).build());
    optionsMap.put(
        "FloatValue", SessionOptionValue.Builder.newBuilder().setFloatValue(99F).build());
    optionsMap.put(
        "Int64Value", SessionOptionValue.Builder.newBuilder().setInt64Value(1000L).build());
    optionsMap.put(
        "Int32Value", SessionOptionValue.Builder.newBuilder().setInt32Value(1004).build());

    // SubstitutionSettings
    final List<String> exclusions = Arrays.asList("a", "b", "c");
    final SubstitutionSettings substitutionSettings = new SubstitutionSettings(exclusions);

    // ClientInfos
    UserBitShared.RpcEndpointInfos rpcEndpointInfos =
        UserBitShared.RpcEndpointInfos.newBuilder()
            .setApplication("Dremio")
            .setBuildNumber(99)
            .setName("Name")
            .setBuildNumber(24)
            .setMajorVersion(13)
            .setMinorVersion(17)
            .setPatchVersion(0)
            .build();

    // UserCredentials
    UserCredentials userCredentials =
        UserBitShared.UserCredentials.newBuilder().setUserName("JohnDoe").build();

    // DefaultSchema
    final List<String> defaultSchema = Arrays.asList("path1", "path2");

    // EngineName
    final String engine = "preview";

    // RecordBatchFormat
    final RecordBatchFormat recordBatchFormat = RecordBatchFormat.DREMIO_23_0;

    // UserProperties
    final String routingTagUserProperty = "test_tag";
    final UserProperties userProperties =
        UserProperties.newBuilder()
            .addProperties(
                UserProtos.Property.newBuilder()
                    .setKey(UserSession.ROUTING_TAG)
                    .setValue(routingTagUserProperty)
                    .build())
            .build();

    // SourceVersionMapping
    final Map<String, VersionContext> stringVersionContextMap = new HashMap<>();
    stringVersionContextMap.put("one", VersionContext.ofBranch("main"));

    UserSession inUserSession =
        UserSession.Builder.newBuilder()
            .withSessionOptions(optionsMap)
            .withSubstitutionSettings(substitutionSettings)
            .exposeInternalSources(true)
            .withClientInfos(rpcEndpointInfos)
            .setSupportComplexTypes(false)
            .withCheckMetadataValidity(true)
            .withCredentials(userCredentials)
            .withDefaultSchema(defaultSchema)
            .withEngineName(engine)
            .withErrorOnUnspecifiedVersion(false)
            .withInitialQuoting(Quoting.DOUBLE_QUOTE)
            .withFullyQualifiedProjectsSupport(true)
            .withLegacyCatalog()
            .withNeverPromote(false)
            .withRecordBatchFormat(recordBatchFormat)
            .withUserProperties(userProperties)
            .withSourceVersionMapping(stringVersionContextMap)
            .build();
    try {
      UserSession outUserSession =
          GrpcUserSessionConverter.fromProtoBuf(GrpcUserSessionConverter.toProtoBuf(inUserSession));

      // SesionOptions
      Assert.assertEquals(optionsMap.size(), outUserSession.getSessionOptionsMap().size());
      Assert.assertEquals(
          inUserSession.getSessionOptionsMap().get("StringListValue").getStringListValue(),
          outUserSession.getSessionOptionsMap().get("StringListValue").getStringListValue());
      Assert.assertEquals(
          inUserSession.getSessionOptionsMap().get("StringValued").getStringValue(),
          outUserSession.getSessionOptionsMap().get("StringValued").getStringValue());
      Assert.assertTrue(
          inUserSession.getSessionOptionsMap().get("DoubleValue").getDoubleValue()
              == outUserSession.getSessionOptionsMap().get("DoubleValue").getDoubleValue());
      Assert.assertEquals(
          inUserSession.getSessionOptionsMap().get("BoolValue").getBoolValue(),
          outUserSession.getSessionOptionsMap().get("BoolValue").getBoolValue());
      Assert.assertTrue(
          inUserSession.getSessionOptionsMap().get("FloatValue").getFloatValue()
              == outUserSession.getSessionOptionsMap().get("FloatValue").getFloatValue());
      Assert.assertEquals(
          inUserSession.getSessionOptionsMap().get("Int64Value").getInt64Value(),
          outUserSession.getSessionOptionsMap().get("Int64Value").getInt64Value());
      Assert.assertEquals(
          inUserSession.getSessionOptionsMap().get("Int32Value").getInt32Value(),
          outUserSession.getSessionOptionsMap().get("Int32Value").getInt32Value());

      // SubstitutionSettings
      Assert.assertEquals(
          substitutionSettings.getExclusions(),
          outUserSession.getSubstitutionSettings().getExclusions());

      // ExposeInternalSources
      Assert.assertTrue(outUserSession.exposeInternalSources());

      // ClientInfos
      Assert.assertTrue(rpcEndpointInfos == outUserSession.getClientInfos());

      // SupportComplexTypes
      Assert.assertFalse(outUserSession.isSupportComplexTypes());

      // CheckMetadataValidity
      Assert.assertTrue(outUserSession.checkMetadataValidity());

      // UserCredentials
      Assert.assertTrue(userCredentials == outUserSession.getCredentials());

      // DefaultSchema
      Assert.assertEquals(
          defaultSchema.stream().collect(Collectors.joining(".")),
          outUserSession.getDefaultSchemaName());

      // EngineName
      Assert.assertTrue(engine.equals(outUserSession.getEngine()));

      // ErrorOnUnspecifiedVersion
      Assert.assertFalse(outUserSession.errorOnUnspecifiedVersion());

      // InitialQuoting
      Assert.assertEquals(Quoting.DOUBLE_QUOTE, outUserSession.getInitialQuoting());

      // FullyQualifiedProjectsSupport
      Assert.assertTrue(outUserSession.supportFullyQualifiedProjections());

      // LegacyCatalog
      Assert.assertTrue(outUserSession.useLegacyCatalogName());

      // NeverPromote - false
      Assert.assertFalse(outUserSession.neverPromote());

      // RecordBatchFormat
      Assert.assertEquals(recordBatchFormat, outUserSession.getRecordBatchFormat());

      // UserProperties
      Assert.assertTrue(routingTagUserProperty.equals(outUserSession.getRoutingTag()));

      // SourceVersionMapping
      Assert.assertEquals(stringVersionContextMap, outUserSession.getSourceVersionMapping());

    } catch (Exception e) {
      Assert.fail("Should not throw an exception");
    }
  }
}
