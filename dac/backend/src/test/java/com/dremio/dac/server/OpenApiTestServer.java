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
package com.dremio.dac.server;

import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.dac.annotations.RestApiServer;
import javax.inject.Inject;

/** Test server on a different path for test resources. */
@RestApiServer(pathSpec = "/api/v4test/*", tags = "oss,enterprise,dcs")
public class OpenApiTestServer extends OpenApiServerImpl {

  @Inject
  public OpenApiTestServer(ScanResult result) {
    super(result);
  }
}
