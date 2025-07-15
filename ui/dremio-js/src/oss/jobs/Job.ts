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
import { filter, lastValueFrom, Observable, share } from "rxjs";
import type { SonarV3Config } from "../../_internal/types/Config.ts";
import type { Problem } from "../../common/Problem.ts";
import { Err, Ok } from "ts-results-es";
import { tableFromArrays } from "apache-arrow";
import { createRowTypeMapper } from "./utils.ts";

export class Job {
  readonly acceleration: JobProperties["acceleration"];
  readonly cancellationReason: JobProperties["cancellationReason"];
  readonly endedAt: JobProperties["endedAt"];
  readonly id: JobProperties["id"];
  readonly problem: JobProperties["problem"];
  readonly queryType: JobProperties["queryType"];
  readonly resourceSchedulingEndedAt: JobProperties["resourceSchedulingEndedAt"];
  readonly resourceSchedulingStartedAt: JobProperties["resourceSchedulingStartedAt"];
  readonly rowCount: JobProperties["rowCount"];
  readonly startedAt: JobProperties["startedAt"];
  readonly state: JobProperties["state"];

  #config: SonarV3Config;

  constructor(properties: JobProperties, config: SonarV3Config) {
    this.acceleration = properties.acceleration;
    this.cancellationReason = properties.cancellationReason;
    this.endedAt = properties.endedAt;
    this.id = properties.id;
    this.problem = properties.problem;
    this.queryType = properties.queryType;
    this.resourceSchedulingEndedAt = properties.resourceSchedulingEndedAt;
    this.resourceSchedulingStartedAt = properties.resourceSchedulingStartedAt;
    this.rowCount = properties.rowCount;
    this.startedAt = properties.startedAt;
    this.state = properties.state;
    this.#config = config;
  }

  get settled() {
    return (
      this.state === "COMPLETED" ||
      this.state === "FAILED" ||
      this.state === "INVALID_STATE" ||
      this.state === "CANCELED"
    );
  }

  #observable = new Observable<Job>((subscriber) => {
    if (this.settled) {
      subscriber.next(this);
      subscriber.complete();
      return;
    }
    let currentState = this.state;
    const update = async () => {
      const updatedJob = await this.#config
        .sonarV3Request(`job/${this.id}`)
        .then((res) => res.json())
        .then(
          (properties) =>
            new Job(jobEntityToProperties(this.id, properties), this.#config),
        );

      if (updatedJob.state !== currentState) {
        currentState = updatedJob.state;
        subscriber.next(updatedJob);
      }

      if (updatedJob.settled) {
        subscriber.complete();
      } else {
        setTimeout(() => {
          update();
        }, 2000);
      }
    };
    update();
  }).pipe(share());

  get results() {
    return {
      jsonBatches: this.#jsonBatches.bind(this),
      recordBatches: this.#recordBatches.bind(this),
    };
  }

  get observable() {
    return this.#observable;
  }

  async *#jsonBatches() {
    if (!this.settled) {
      try {
        await lastValueFrom(
          this.observable.pipe(filter((job) => job.state === "COMPLETED")),
        );
      } catch (e) {
        throw new Error("Job failed");
      }
    }

    const batch_size = 500;
    let hasMore = true;
    let offset = 0;
    while (hasMore) {
      const batch = await this.#config
        .sonarV3Request(
          `job/${this.id}/results?offset=${offset}&limit=${batch_size}`,
        )
        .then((res) => res.json());

      offset += batch.rows.length;
      hasMore = batch.rows.length === batch_size;

      if (batch.rows.length) {
        const schema = { fields: batch.schema } as {
          fields: { name: string; type: { name: string } }[];
        };
        const rowTypeMapper = createRowTypeMapper(schema);
        const rows = [...batch.rows];
        for (const row of rows) {
          rowTypeMapper(row);
        }
        yield {
          get columns() {
            const colMap: any = new Map<string, unknown>();
            for (const field of batch.schema) {
              switch (field.type.name) {
                case "BIGINT":
                  colMap.set(field.name, new BigInt64Array(rows.length));
                  break;
                case "DOUBLE":
                  colMap.set(field.name, new Float64Array(rows.length));
                  break;
                default:
                  colMap.set(field.name, Array.from({ length: rows.length }));
                  break;
              }
            }
            rows.forEach((row: any, i: number) => {
              for (const [field, value] of Object.entries(row)) {
                colMap.get(field)[i] = value;
              }
            });
            return colMap;
          },
          rows,
          schema,
        };
      }
    }
  }

  async *#recordBatches() {
    for await (const batch of this.#jsonBatches()) {
      yield tableFromArrays(Object.fromEntries(batch.columns));
    }
  }

  async cancel() {
    return this.#config
      .sonarV3Request(`job/${this.id}/cancel`, {
        keepalive: true,
        method: "POST",
      })
      .then(() => Ok(undefined))
      .catch((e) => Err(e));
  }
}

export const jobEntityToProperties = (
  id: string,
  entity: {
    jobState:
      | "NOT_SUBMITTED"
      | "STARTING"
      | "RUNNING"
      | "COMPLETED"
      | "CANCELED"
      | "FAILED"
      | "CANCELLATION_REQUESTED"
      | "PLANNING"
      | "PENDING"
      | "METADATA_RETRIEVAL"
      | "QUEUED"
      | "ENGINE_START"
      | "EXECUTION_PLANNING"
      | "INVALID_STATE";
    rowCount: number | null;
    errorMessage: string;
    startedAt: string | null;
    endedAt: string | null;
    acceleration: {
      reflectionRelationships: {
        datasetId: string;
        reflectionId: string;
        relationship: string;
      }[];
    };
    queryType:
      | "UI_RUN"
      | "UI_PREVIEW"
      | "UI_INTERNAL_PREVIEW"
      | "UI_INTERNAL_RUN"
      | "UI_EXPORT"
      | "ODBC"
      | "JDBC"
      | "REST"
      | "ACCELERATOR_CREATE"
      | "ACCELERATOR_DROP"
      | "UNKNOWN"
      | "PREPARE_INTERNAL"
      | "ACCELERATOR_EXPLAIN"
      | "UI_INITIAL_PREVIEW";
    resourceSchedulingStartedAt: string | null;
    resourceSchedulingEndedAt: string | null;
    cancellationReason: string | null;
  },
) =>
  ({
    acceleration: entity.acceleration,
    cancellationReason: entity.cancellationReason,
    endedAt: entity.endedAt ? new Date(entity.endedAt) : null,
    id,
    problem: null as Problem | null,
    queryType: entity.queryType,
    resourceSchedulingEndedAt: entity.resourceSchedulingEndedAt
      ? new Date(entity.resourceSchedulingEndedAt)
      : null,
    resourceSchedulingStartedAt: entity.resourceSchedulingStartedAt
      ? new Date(entity.resourceSchedulingStartedAt)
      : null,
    rowCount: entity.rowCount,
    startedAt: entity.startedAt ? new Date(entity.startedAt) : null,
    state: entity.jobState,
  }) as const;

type JobProperties = ReturnType<typeof jobEntityToProperties>;
