//
// Copyright (C) 2017-2019 Dremio Corporation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

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
//eg /source/mongo/dataset/mongo.academic.business

class DatasetResourcePathUtils {
  toFullPath(resourcePath) {
    const parts = resourcePath.split("/");
    return parts[2];
  }

  toHref(resourcePath) {
    const fullPath = this.toFullPath(resourcePath);
    const pathParts = fullPath.split(".");
    return `/space/${pathParts.slice(0, pathParts.length - 1).join(".")}/${
      pathParts[pathParts.length - 1]
    }`;
  }

  /*
   * Creating the dataset URL: DX-56494
   *
   * Decoding the part separately before concatenating into a URL. Putting
   * the roote space before the rest of the namespace, so that
   * getInitialDataset (src/selectors/explore.js) parses the route params correctly.
   *
   */
  toHrefV2(datasetFullPath) {
    const encodedDatasetFullPath = datasetFullPath
      .toJS()
      .map((item) =>
        encodeURIComponent(item.includes(".") ? `"${item}"` : item),
      );
    const rootSpace = encodedDatasetFullPath.shift();
    return `/space/${rootSpace}/${encodedDatasetFullPath.join(".")}`;
  }
}

const result = new DatasetResourcePathUtils();
export default result;
