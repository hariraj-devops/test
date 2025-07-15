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
package com.dremio.io.file;

/** Listing of schemes supported in Dremio. */
public interface UriSchemes {
  String HDFS_SCHEME = "hdfs";
  String MAPRFS_SCHEME = "maprfs";
  String WEBHDFS_SCHEME = "webhdfs";
  String FILE_SCHEME = "file";
  String S3_SCHEME = "s3";
  String S3A_SCHEME = "s3a";
  String AZURE_ABFSS_SCHEME = "abfss";

  /** Dremio does not write tables with the unsecure option */
  String AZURE_ABFS_SCHEME = "abfs";

  String AZURE_WASBS_SCHEME = "wasbs";

  /** Dremio does not write tables with the unsecure option */
  String AZURE_WASB_SCHEME = "wasb";

  String GCS_SCHEME = "gs";

  String DREMIO_GCS_SCHEME = "dremiogcs";
  String DREMIO_S3_SCHEME = "dremioS3";
  String DREMIO_AZURE_SCHEME = "dremioAzureStorage";
  String DREMIO_HDFS_SCHEME = HDFS_SCHEME;

  String LOCALHOST_LOOPBACK = "localhost";
  String CLASS_PATH_FILE_SYSTEM = "classpath";
  String LEGACY_PDFS_SCHEME = "pdfs";

  String SCHEME_SEPARATOR = "://";
}
