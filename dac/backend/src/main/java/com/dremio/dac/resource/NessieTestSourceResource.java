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
package com.dremio.dac.resource;

import com.dremio.exec.store.CatalogService;
import com.dremio.options.OptionManager;
import com.dremio.services.nessie.proxy.ProxyV2TreeResource;
import javax.inject.Inject;
import javax.ws.rs.core.HttpHeaders;
import org.projectnessie.client.api.NessieApiV2;

/** Resource for providing APIs for Nessie as a Source for Test. */
public class NessieTestSourceResource extends NessieSourceResource {

  private final HttpHeaders headers;

  @Inject
  public NessieTestSourceResource(
      CatalogService catalogService, OptionManager optionManager, HttpHeaders headers) {
    super(catalogService, optionManager, headers);
    this.headers = headers;
  }

  @Override
  protected ProxyV2TreeResource getTreeResource(NessieApiV2 nessieApi) {
    return new ProxyV2TreeResource(nessieApi, headers);
  }
}
