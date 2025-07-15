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
package com.dremio.catalog.exception;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;

public class SourceDoesNotExistException extends CatalogException {
  private final String sourceName;

  public SourceDoesNotExistException(String sourceName, Exception error) {
    super(String.format("Source [%s] does not exist.", sourceName), error);
    this.sourceName = sourceName;
  }

  public SourceDoesNotExistException(String sourceName) {
    this(sourceName, null);
  }

  public String getSourceName() {
    return sourceName;
  }

  @Override
  public WebApplicationException toRestApiException() {
    return new NotFoundException(getMessage(), this);
  }
}
