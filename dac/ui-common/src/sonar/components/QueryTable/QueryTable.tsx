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

import clsx from "clsx";
import { type FC, type HTMLProps } from "react";
import { createTable } from "leantable/core";
import { Table, type Column } from "leantable/react";
import { Skeleton, SyntaxHighlighter, Tooltip } from "dremio-ui-lib/components";
import { ContainerSplash } from "../../../components/ContainerSplash";
import { WithClickableCell } from "../../../components/TableCells/ClickableCell";
import { DurationCell } from "../../../components/TableCells/DurationCell";
import { TimestampCellShortNoTZ } from "../../../components/TableCells/TimestampCell";
import { getIntlContext } from "../../../contexts/IntlContext";
import { JobStatus, type JobStates } from "../JobStatus";
import { RealTimer } from "../RealTimer";
import { ReflectionIcon } from "../ReflectionIcon";

type PendingQuery = { sql: string };

type Query = {
  accelerated?: boolean;
  duration: number;
  isComplete?: boolean;
  jobId: string;
  queryType: "UI_RUN" | "UI_PREVIEW";
  spilled?: boolean;
  sql: string;
  startTime: number;
  status: JobStates;
};

const { t } = getIntlContext();

const isPendingQuery = (query: PendingQuery | Query): query is PendingQuery =>
  !(query as Query).status;

const queryTable = createTable([(x: unknown) => x]);

const getQueryColumns = (onQueryClick?: (index: number) => void) =>
  [
    {
      id: "sql",
      renderHeaderCell: () => (
        <div style={{ paddingLeft: "28px" }}>
          {t("Sonar.Job.Column.SQL.Label")}
        </div>
      ),
      renderCell: (row) => (
        <WithClickableCell
          onClick={onQueryClick && (() => onQueryClick(parseInt(row.id)))}
        >
          <div className="flex items-center gap-05">
            {isPendingQuery(row.data) ? (
              <span className="h-3 w-3" />
            ) : (
              <JobStatus state={(row.data as Query).status} />
            )}
            <Tooltip
              content={
                <div
                  style={{
                    maxWidth: "50ch",
                    maxHeight: "200px",
                    overflowY: "auto",
                    overflowX: "hidden",
                    overflowWrap: "break-word",
                  }}
                >
                  <SyntaxHighlighter language="sql" wrapLongLines>
                    {row.data.sql.trim()}
                  </SyntaxHighlighter>
                </div>
              }
              interactive
              portal={!!onQueryClick}
              rich
            >
              <span
                className="font-mono text-sm truncate"
                style={{ maxInlineSize: "30vw" }}
              >
                {row.data.sql.trim()}
              </span>
            </Tooltip>
          </div>
        </WithClickableCell>
      ),
      class: clsx(
        onQueryClick && "leantable-sticky-column leantable-sticky-column--left",
      ),
    },
    {
      id: "accelerated",
      renderHeaderCell: () => <ReflectionIcon />,
      renderCell: (row) => {
        if (isPendingQuery(row.data)) {
          return;
        }

        return (row.data as Query).accelerated ? <ReflectionIcon /> : null;
      },
    },
    {
      id: "queryType",
      renderHeaderCell: () => t("Sonar.Job.Column.QueryType.Label"),
      renderCell: (row) => {
        if (isPendingQuery(row.data)) {
          return <Skeleton width="10ch" />;
        }

        return (row.data as Query).queryType === "UI_RUN"
          ? t("Sonar.Job.QueryType.UIRun")
          : t("Sonar.Job.QueryType.UIPreview");
      },
    },
    {
      id: "startTime",
      renderHeaderCell: () => t("Sonar.Job.Column.StartTime.Label"),
      renderCell: (row) => {
        if (isPendingQuery(row.data)) {
          return <Skeleton width="17ch" />;
        }

        return (
          <TimestampCellShortNoTZ
            timestamp={new Date((row.data as Query).startTime)}
          />
        );
      },
    },
    {
      id: "duration",
      renderHeaderCell: () => t("Sonar.Job.Column.Duration.Label"),
      renderCell: (row) => {
        if (isPendingQuery(row.data)) {
          return <Skeleton width="10ch" />;
        }

        if ((row.data as Query).isComplete) {
          return (
            <div className="flex items-center gap-05">
              <DurationCell duration={(row.data as Query).duration} />
              {(row.data as Query).spilled && (
                <Tooltip content={t("Sonar.Job.Column.Duration.SpilledLabel")}>
                  <dremio-icon name="interface/disk-spill" class="h-3 w-3" />
                </Tooltip>
              )}
            </div>
          );
        } else {
          return <RealTimer startTime={(row.data as Query).startTime} />;
        }
      },
    },
    {
      id: "jobId",
      renderHeaderCell: () => t("Sonar.Job.Column.JobId.Label"),
      renderCell: (row) => {
        if (isPendingQuery(row.data)) {
          return <Skeleton width="14ch" />;
        }

        return (
          <Tooltip content={(row.data as Query).jobId}>
            <div
              style={{
                maxInlineSize: "14ch",
                overflow: "hidden",
                textOverflow: "ellipsis",
                direction: "rtl",
              }}
            >
              {(row.data as Query).jobId}
            </div>
          </Tooltip>
        );
      },
    },
  ] as const satisfies Column<PendingQuery | Query>[];

const getQueryActions = (
  queryActions: Column<PendingQuery | Query>["renderCell"],
) =>
  [
    {
      id: "actions",
      renderHeaderCell: () => null,
      renderCell: queryActions,
      class:
        "leantable-row-hover-visibility leantable-sticky-column leantable-sticky-column--right leantable--align-right",
    },
  ] as const satisfies Column<PendingQuery | Query>[];

export const QueryTable: FC<
  {
    onQueryClick?: (index: number) => void;
    pendingQueries: PendingQuery[];
    queries: Query[];
    queryActions?: Column<PendingQuery | Query>["renderCell"];
  } & HTMLProps<HTMLDivElement>
> = (props) => {
  const allQueries = [...props.queries, ...props.pendingQueries] as const;

  return !allQueries.length ? (
    <ContainerSplash title={t("Sonar.Query.Table.Empty")} />
  ) : (
    <div className={clsx("flex flex-col overflow-auto", props.className)}>
      <Table
        {...queryTable}
        className="leantable--fixed-header"
        columns={[
          ...getQueryColumns(props.onQueryClick),
          ...(props.queryActions ? getQueryActions(props.queryActions) : []),
        ]}
        rowCount={allQueries.length}
        getRow={(i: number) => ({
          id: i,
          data: allQueries[i],
        })}
      />
      {!!props.pendingQueries.length && (
        <div
          className="flex items-center justify-center bg-primary h-5 w-full"
          style={{
            borderTop: "1px solid var(--border--neutral)",
            minHeight: "var(--scale-5)",
            position: "sticky",
            left: "0",
            bottom: "0",
            zIndex: "350", // should match z-index for leantable--fixed-header leantable-sticky-column
          }}
        >
          {t("Sonar.Query.Table.Pending", {
            pending: props.queries.length,
            total: allQueries.length,
          })}
        </div>
      )}
    </div>
  );
};
