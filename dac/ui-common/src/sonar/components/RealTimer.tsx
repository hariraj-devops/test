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

import { useEffect, useRef, useState, type FC, type ReactNode } from "react";
import { NumericCell } from "../../components/TableCells/NumericCell";
import { formatDurationUnderSecond } from "../../utilities/formatDuration";

/**
 * @returns A formatted counter of how much time has elapsed since the starting time
 */
export const RealTimer: FC<
  Partial<{
    startTime: number;
    updateInterval: number;
    formatter: (timeElapsed: number) => ReactNode;
  }>
> = ({
  startTime = Date.now(),
  updateInterval = 1000,
  formatter = formatDurationUnderSecond,
}) => {
  const startRef = useRef(startTime);
  const [curTime, setCurTime] = useState(Date.now());

  useEffect(() => {
    const realInterval = setInterval(() => {
      setCurTime(Date.now());
    }, updateInterval);

    return () => clearInterval(realInterval);
  }, [updateInterval]);

  return <NumericCell>{formatter(curTime - startRef.current)}</NumericCell>;
};
