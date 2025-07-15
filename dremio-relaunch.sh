#!/bin/bash
#
# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#


set -e  # Exit on any error
set -o pipefail  # Catch errors in pipe

# Step 1: Build Dremio UI
echo "🔧 Building Dremio UI..."
cd /opt/dremio/dac/ui

if [ "$1" = "build" ]; then
	npm run build
fi

if [ "$2" = "install" ]; then
	npm install
fi
#npm install

mkdir -p /opt/dremio-logs/

# Step 2: Clean Maven local repository
echo "🧹 Cleaning ~/.m2/repository/..."
rm -rf ~/.m2/repository/

# Step 3: Start Dremio backend build in a detached screen
echo "🚀 Starting Dremio backend build in screen session..."

BUILD_LOG="/opt/dremio-logs/dremio_build-$(date +%Y%m%d-%H%M%S).log"
screen -dmS dremio bash -c "cd /opt/dremio && mvn clean install -DskipTests | tee \"$BUILD_LOG\""

echo "✅ Build started in background. Log: $BUILD_LOG"
echo "tail -f /opt/dremio/distribution/server/target/dremio-community-26.0.0-202504290223270716-afdd6663/dremio-community-26.0.0-202504290223270716-afdd6663/log/server.out" 
