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
import { createRef, PureComponent } from "react";
import PropTypes from "prop-types";
import { FormattedMessage, injectIntl } from "react-intl";

import { formDescription } from "uiTheme/radium/typography";

import {
  FLEX_NOWRAP_ROW_SPACE_BETWEEN_START,
  FLEX_NOWRAP_CENTER_START,
} from "uiTheme/radium/flexStyle";
import fileUtils, { BYTES_IN_MB } from "utils/fileUtils/fileUtils";

import Dropzone from "react-dropzone";

const PROGRESS_BAR_WIDTH = 180;

class FileField extends PureComponent {
  static propTypes = {
    style: PropTypes.object,
    value: PropTypes.oneOfType([PropTypes.string, PropTypes.object]),
    onChange: PropTypes.func,
    intl: PropTypes.object.isRequired,
  };

  constructor(props) {
    super(props);
    this.dropzoneRef = createRef();
    this.state = {
      loadProgressTime: 0,
      total: 0,
      loaded: 0,
      loadSpeed: 0,
    };
  }

  onOpenClick = () => {
    this.dropzoneRef.current.open();
  };

  onDrop = (f) => {
    const file = f[0];
    const reader = new FileReader();
    reader.onprogress = this.updateProgress;
    reader.onloadend = () => {
      this.props.onChange(file, false);
      this.endProgress();
    };
    reader.onloadstart = () => {
      this.props.onChange(file, true);
      this.startProgress();
    };
    reader.readAsText(file);
  };

  getFileName(value) {
    if (value && value.name) {
      return value.name;
    }
    return "";
  }

  getFileSize(value) {
    if (value && value.size) {
      return fileUtils.convertFileSize(value.size);
    }
    return "";
  }

  startProgress = () => {
    this.setState({
      loadProgressTime: new Date().getTime(),
    });
  };

  endProgress = () => {
    this.setState({
      loadProgressTime: 0,
      total: 0,
      loaded: 0,
      loadSpeed: 0,
    });
  };

  updateProgress = (event) => {
    const currentTime = new Date().getTime();
    const loaded = Number(event.loaded);

    this.setState({
      total: Number(event.total),
      loaded,
      loadSpeed: this.calculateLoadSpeed(loaded, currentTime),
      loadProgressTime: currentTime,
    });
  };

  calculateLoadSpeed(loaded, currentTime) {
    const loadDiff = (loaded - this.state.loaded) / BYTES_IN_MB;
    const timeDiff = (currentTime - this.state.loadProgressTime) / 1000;
    return Number(loadDiff / timeDiff).toFixed(1);
  }

  renderFileInfo(value) {
    if (!value) {
      return null;
    }
    return (
      <div style={styles.fileInfo}>
        <span>{this.getFileName(value)} </span>
        <b>{this.getFileSize(value)}</b>
      </div>
    );
  }

  renderProgressBar() {
    const { total, loaded, loadSpeed } = this.state;

    if (total === 0) {
      return null;
    }
    const progress = Math.round((loaded / total) * 100) + "%";
    return (
      <div>
        <progress value={loaded} max={total} style={styles.progressBar} />
        <div style={{ display: "flex", justifyContent: "space-between" }}>
          <span>{progress}</span>
          <span>{`${loadSpeed}MB/s`}</span>
        </div>
      </div>
    );
  }

  render() {
    const { style, value } = this.props; // todo: loc with sub patterns
    const inProgressStyle = this.state.total
      ? { background: "var(--fill--brand)", borderStyle: "solid" }
      : { borderStyle: "dashed" };
    return (
      <div className="field" style={{ ...styles.base, ...(style || {}) }}>
        <Dropzone
          ref={this.dropzoneRef}
          onDrop={this.onDrop}
          disableClick
          multiple={false}
          style={{ ...styles.dropTarget, ...inProgressStyle }}
        >
          <dremio-icon
            name="interface/upload"
            style={{
              blockSize: 75,
              inlineSize: 90,
            }}
          ></dremio-icon>
          <div
            style={{
              ...FLEX_NOWRAP_CENTER_START,
              ...formDescription,
              whiteSpace: "pre",
            }}
          >
            <span>
              <FormattedMessage id="File.DropLocalFile" />{" "}
            </span>{" "}
            {/* todo: loc better (sentence should be one string) */}
            <a onClick={this.onOpenClick}>
              <FormattedMessage id="File.Browse" />
            </a>
            .
          </div>
          {this.renderFileInfo(value)}
          {/* should be upload progress, not read this.renderProgressBar() */}
        </Dropzone>
      </div>
    );
  }
}
const styles = {
  progressBar: {
    width: PROGRESS_BAR_WIDTH,
    height: 5,
    marginBottom: 5,
  },
  fileInfo: {
    marginTop: 10,
    minWidth: PROGRESS_BAR_WIDTH,
    padding: "5px 10px",
    borderRadius: 3,
    border: `2px solid var(--border--neutral)`,
    display: "flex",
    justifyContent: "space-between",
    backgroundColor: "#fff",
    whiteSpace: "pre",
    color: "#000",
  },
  base: { ...FLEX_NOWRAP_ROW_SPACE_BETWEEN_START },
  dropTarget: {
    height: 270,
    display: "flex",
    alignItems: "center",
    flexDirection: "column",
    width: "100%",
    paddingTop: 85,
    border: "1px dashed #A1DBE4",
    marginBottom: 16,
    cursor: "pointer",
    ...formDescription,
  },
};
export default injectIntl(FileField);
