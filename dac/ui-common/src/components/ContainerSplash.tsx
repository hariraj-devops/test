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
type ContainerSplashProps = {
  image?: JSX.Element;
  title: string | JSX.Element;
  details?: JSX.Element | string;
  action?: JSX.Element;
};

export const ContainerSplash = (props: ContainerSplashProps): JSX.Element => {
  return (
    <div className="centered-container" style={{ maxBlockSize: "460px" }}>
      <div
        className="dremio-prose"
        style={{
          textAlign: "center",
        }}
      >
        {props.image && <div className="no-select">{props.image}</div>}
        <div className="dremio-typography-large dremio-typography-bold">
          {props.title}
        </div>
        {props.details && (
          <div className="dremio-typography-less-important">
            {props.details}
          </div>
        )}
        {props.action && <div>{props.action}</div>}
      </div>
    </div>
  );
};
