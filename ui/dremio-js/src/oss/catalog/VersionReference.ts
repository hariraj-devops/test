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

export type BranchHeadVersionReference = {
  branch: string;
};

export type BareCommitVersionReference = {
  hash: string;
};

export type TagVersionReference = {
  tag: string;
};

export type VersionReference =
  | BareCommitVersionReference
  | BranchHeadVersionReference
  | TagVersionReference;

export const isBranchHeadVersionReference = (
  versionReference: VersionReference,
): versionReference is BranchHeadVersionReference =>
  !("hash" in versionReference) && "branch" in versionReference;

export const isBareCommitVersionReference = (
  versionReference: VersionReference,
): versionReference is BareCommitVersionReference =>
  "hash" in versionReference && !("branch" in versionReference);

export const isTagVersionReference = (
  versionReference: VersionReference,
): versionReference is TagVersionReference => "tag" in versionReference;
