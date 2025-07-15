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
package com.dremio.dac.api;

import com.dremio.api.ImmutableError;
import com.dremio.dac.annotations.OpenApiResource;
import com.dremio.example.api.ImmutableStorageObjects;
import com.dremio.example.api.StorageObject;
import com.dremio.example.api.StorageObjects;
import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import javax.ws.rs.WebApplicationException;

@OpenApiResource(serverPathSpec = "/api/v4test/*")
public class ExampleV4ResourceImpl implements com.dremio.dac.api.ExampleV4Resource {
  private static final int MAX_OBJECTS = 100;

  private static final Map<String, StorageObject> OBJECTS = new TreeMap<>();

  /** Deletes an object. */
  @Override
  public void delete(String path) {
    synchronized (OBJECTS) {
      StorageObject object = OBJECTS.get(path);
      if (object == null) {
        throw new WebApplicationException(
            ErrorResponseConverter.notFound(String.format("Path %s does not exist", path)));
      }
      OBJECTS.remove(path);
    }
  }

  /** Creates an object at the path. */
  @Override
  public StorageObject post(StorageObject request, String path) {
    synchronized (OBJECTS) {
      // Pre-defined error.
      if (OBJECTS.containsKey(path)) {
        throw new WebApplicationException(
            ErrorResponseConverter.conflict(String.format("Path %s exists", path)));
      }

      // Custom error.
      if (!request.getPath().equals(path)) {
        throw new WebApplicationException(
            ErrorResponseConverter.validationError(
                ImmutableList.of(
                    ImmutableError.builder()
                        .detail("Incorrect parameter")
                        .pointer("path")
                        .build())));
      }

      if (OBJECTS.size() >= MAX_OBJECTS) {
        throw new WebApplicationException(
            ErrorResponseConverter.validationError(
                ImmutableList.of(ImmutableError.builder().detail("Too many records.").build())));
      }

      // Can modify it before persistence.
      OBJECTS.put(path, request);

      return request;
    }
  }

  /** List objects. */
  @Override
  public StorageObjects get(String listFilter) {
    synchronized (OBJECTS) {
      return ImmutableStorageObjects.builder()
          .items(
              OBJECTS.values().stream()
                  .sorted(Comparator.comparing(o -> o.getPath()))
                  .collect(ImmutableList.toImmutableList()))
          .build();
    }
  }

  /** Gets example object. */
  @Override
  public StorageObject get1(String path) {
    synchronized (OBJECTS) {
      StorageObject object = OBJECTS.get(path);
      if (object == null) {
        throw new WebApplicationException(
            ErrorResponseConverter.notFound(String.format("Path %s does not exist", path)));
      }
      return object;
    }
  }
}
