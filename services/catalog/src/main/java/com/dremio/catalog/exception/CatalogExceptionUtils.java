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

import com.dremio.service.namespace.InvalidNamespaceNameException;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceInvalidStateException;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.RemoteNamespaceException;

public final class CatalogExceptionUtils {
  public static CatalogException fromNamespaceException(NamespaceException namespaceException) {
    if (namespaceException instanceof InvalidNamespaceNameException) {
      return new CatalogInvalidNameException(namespaceException.getMessage(), namespaceException);
    } else if (namespaceException instanceof NamespaceInvalidStateException) {
      return new CatalogInvalidStateException(namespaceException.getMessage(), namespaceException);
    } else if (namespaceException instanceof NamespaceNotFoundException) {
      return new CatalogEntityNotFoundException(
          namespaceException.getMessage(), namespaceException);
    } else if (namespaceException instanceof RemoteNamespaceException) {
      return new CatalogInvalidStateException(namespaceException.getMessage(), namespaceException);
    }
    return new CatalogInvalidStateException(namespaceException.getMessage(), namespaceException);
  }
}
