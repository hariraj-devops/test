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

import "@dotlottie/player-component";
import { Tooltip } from "dremio-ui-lib";

type LottieImagesProps = {
  src: any;
  alt?: string;
  title?: any;
  interactive?: boolean;
  tooltipOpen?: boolean;
  imageHeight?: number;
  imageWidth?: number;
  style?: any;
  wrapperClassname?: string;
};

const LottieImages = ({
  src,
  alt,
  interactive,
  tooltipOpen,
  imageHeight,
  imageWidth,
  style,
  wrapperClassname,
  ...props
}: LottieImagesProps) => {
  let { title } = props;
  if (title === true) {
    title = alt;
  }

  const Animation = (
    <dotlottie-player
      src={src}
      loop
      autoplay
      mode="normal"
      key={src}
      style={{
        ...(imageWidth && { width: `${imageWidth}px` }),
        ...(imageHeight && { height: `${imageHeight}px` }),
        ...style,
      }}
    ></dotlottie-player>
  );

  return title ? (
    <div className={wrapperClassname}>
      <Tooltip title={title} interactive={interactive} open={tooltipOpen}>
        <div>{Animation}</div>
      </Tooltip>
    </div>
  ) : (
    <div className={wrapperClassname}>{Animation}</div>
  );
};

export default LottieImages;
