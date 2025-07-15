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
package com.dremio.exec.store.dfs.system;

import static com.dremio.exec.ExecConstants.SYSTEM_ICEBERG_TABLES_COMMIT_NUM_RETRIES;

import com.dremio.exec.ExecConstants;
import com.dremio.exec.store.dfs.copyinto.CopyFileHistoryTableMetadata;
import com.dremio.exec.store.dfs.copyinto.CopyJobHistoryTableMetadata;
import com.dremio.options.OptionManager;
import java.util.Arrays;
import java.util.List;
import org.apache.iceberg.exceptions.NotFoundException;

/** Utility class for initializing the table metadata for system iceberg tables. */
public final class SystemIcebergTableMetadataFactory {

  public enum SupportedSystemIcebergTable {
    COPY_JOB_HISTORY("copy_job_history"),
    COPY_FILE_HISTORY("copy_file_history");

    private final String tableName;

    SupportedSystemIcebergTable(String tableName) {
      this.tableName = tableName;
    }

    public String getTableName() {
      return tableName;
    }
  }

  public static SystemIcebergTableMetadata getTableMetadata(
      String pluginName,
      String pluginPath,
      OptionManager optionManager,
      List<String> tableSchemaPath) {
    long schemaVersion =
        optionManager.getOption(ExecConstants.SYSTEM_ICEBERG_TABLES_SCHEMA_VERSION);
    long commitRetries = optionManager.getOption(SYSTEM_ICEBERG_TABLES_COMMIT_NUM_RETRIES);
    if (tableSchemaPath.stream()
        .anyMatch(
            p -> p.equalsIgnoreCase(SupportedSystemIcebergTable.COPY_JOB_HISTORY.getTableName()))) {
      return new CopyJobHistoryTableMetadata(
          schemaVersion,
          commitRetries,
          pluginName,
          pluginPath,
          SupportedSystemIcebergTable.COPY_JOB_HISTORY.getTableName());
    } else if (tableSchemaPath.stream()
        .anyMatch(
            p ->
                p.equalsIgnoreCase(SupportedSystemIcebergTable.COPY_FILE_HISTORY.getTableName()))) {
      return new CopyFileHistoryTableMetadata(
          schemaVersion,
          commitRetries,
          pluginName,
          pluginPath,
          SupportedSystemIcebergTable.COPY_FILE_HISTORY.getTableName());
    }
    throw new NotFoundException("Invalid system iceberg table : %s", tableSchemaPath);
  }

  private SystemIcebergTableMetadataFactory() {
    // Not to be instantiated
  }

  public static boolean isSupportedTablePath(List<String> tableSchemaPath) {
    return Arrays.stream(SupportedSystemIcebergTable.values())
        .map(SupportedSystemIcebergTable::getTableName)
        .anyMatch(
            supportedTableName ->
                tableSchemaPath.stream().anyMatch(supportedTableName::equalsIgnoreCase));
  }
}
