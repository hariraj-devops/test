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

import { type FC } from "react";
// import { ErrorBoundary } from "react-error-boundary";
// import { SectionMessage, Spinner } from "dremio-ui-lib/components";
// import { CatalogTree } from "#oss/exports/components/CatalogTree/CatalogTree";

export const CatalogTreeContainer: FC<{
  sort: "asc" | "desc";
  starsOnly: boolean;
  oldRenderHome: () => JSX.Element | undefined;
  oldRenderItems: () => JSX.Element | JSX.Element[] | undefined;
}> = (props) => (
  <div className="TreeBrowser-items">
    {props.oldRenderHome()}
    {props.oldRenderItems()}
  </div>
);

//   <Suspense
//     fallback={
//       <Spinner className="flex flex-row items-center justify-center h-10" />
//     }
//   >
//     <ErrorBoundary
//       fallbackRender={({ error }) => (
//         <SectionMessage appearance="danger" className="mx-105 mt-05">
//           {`Error: ${error.message}`}
//         </SectionMessage>
//       )}
//     >
//       <CatalogTree sort={props.sort} starsOnly={props.starsOnly} />
//     </ErrorBoundary>
//   </Suspense>
