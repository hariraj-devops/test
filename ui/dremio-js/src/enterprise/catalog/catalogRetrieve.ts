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

import { batch } from "@e3m-io/batch-fn-calls";
import { Err, Ok, type Result } from "ts-results-es";
import type { SonarV3Config } from "../../_internal/types/Config.ts";
import { catalogObjectFromEntity } from "./catalogObjectFromEntity.ts";
import { HttpError } from "../../common/HttpError.ts";
import type { EnterpriseCatalogObject } from "./CatalogObjects/index.ts";
import { unableToRetrieveProblem } from "../../oss/catalog/catalogErrors.ts";

export const baseRetrieve = (config: SonarV3Config) =>
  batch(async (ids: Set<string>) => {
    const results = new Map<string, Result<EnterpriseCatalogObject, unknown>>();

    if (ids.size === 1) {
      const id = Array.from(ids).at(0)!;
      return config
        .sonarV3Request(`catalog/${id}?maxChildren=0`)
        .then((res) => res.json())
        .then((response) => {
          results.set(
            id,
            Ok(
              catalogObjectFromEntity(
                config,
                baseRetrieveByPath(config),
              )(response) as any,
            ),
          );
          return results;
        })
        .catch((e) => {
          if (e instanceof HttpError && (e.body as any)?.detail) {
            results.set(
              id,
              Err(unableToRetrieveProblem((e.body as any).detail)),
            );
          } else {
            results.set(id, Err(unableToRetrieveProblem()));
          }

          return results;
        });
    }

    const idsArray = Array.from(ids);
    const chunks = [];

    while (idsArray.length > 0) {
      chunks.push(idsArray.splice(0, Math.min(50, idsArray.length)));
    }

    const catalogItems = await Promise.all(
      chunks.map((chunk) =>
        config
          .sonarV3Request(`catalog/by-ids?maxChildren=0`, {
            body: JSON.stringify(chunk),
            headers: { "Content-Type": "application/json" },
            method: "POST",
          })
          .then((res) => res.json())
          .then((response) => response.data),
      ),
    ).then((chunks) => chunks.flat());

    for (const catalogItem of catalogItems) {
      results.set(
        catalogItem.id,
        Ok(
          catalogObjectFromEntity(
            config,
            baseRetrieveByPath(config),
          )(catalogItem) as any,
        ),
      );
    }

    for (const id of ids) {
      if (!results.has(id)) {
        results.set(id, Err(unableToRetrieveProblem()));
      }
    }

    return results;
  });

export const baseRetrieveByPath = (config: SonarV3Config) =>
  batch(async (paths: Set<string[]>) => {
    const results = new Map<
      string[],
      Result<EnterpriseCatalogObject, unknown>
    >();

    // Because the key is a reference type (array), we need to store the
    // original path reference
    const originalKeyRef = new Map<string, string[]>();
    for (const key of paths) {
      originalKeyRef.set(JSON.stringify(key), key);
    }

    if (paths.size === 1) {
      const path = Array.from(paths).at(0)!;
      return config
        .sonarV3Request(
          `catalog/by-path/${path.map(encodeURIComponent).join("/")}?maxChildren=0`,
        )
        .then((res) => res.json())
        .then((response) => {
          results.set(
            path,
            Ok(
              catalogObjectFromEntity(
                config,
                baseRetrieveByPath(config),
              )(response),
            ) as any,
          );
          return results;
        })
        .catch((e) => {
          if (e instanceof HttpError && (e.body as any)?.detail) {
            results.set(
              path,
              Err(unableToRetrieveProblem((e.body as any).detail)),
            );
          } else {
            results.set(path, Err(unableToRetrieveProblem()));
          }
          return results;
        });
    }

    const pathsArray = Array.from(paths);
    const chunks = [];

    while (pathsArray.length > 0) {
      chunks.push(pathsArray.splice(0, Math.min(50, pathsArray.length)));
    }

    const catalogItems = await Promise.all(
      chunks.map((chunk) =>
        config
          .sonarV3Request(`catalog/by-paths?maxChildren=0`, {
            body: JSON.stringify(chunk),
            headers: { "Content-Type": "application/json" },
            method: "POST",
          })
          .then((res) => res.json())
          .then((response) => response.data),
      ),
    ).then((chunks) => chunks.flat());

    for (const catalogItem of catalogItems) {
      const resolved = catalogObjectFromEntity(
        config,
        baseRetrieveByPath(config),
      )(catalogItem);
      const originalKey = originalKeyRef.get(JSON.stringify(resolved.path));
      if (originalKey) {
        results.set(originalKey, Ok(resolved) as any);
      }
    }

    for (const path of paths) {
      if (!results.has(path)) {
        results.set(path, Err(unableToRetrieveProblem()));
      }
    }

    return results;
  });
