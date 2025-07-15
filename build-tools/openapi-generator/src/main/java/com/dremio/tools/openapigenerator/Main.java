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
package com.dremio.tools.openapigenerator;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.dremio.tools.openapigenerator.java.JavaProtoGenerator;
import com.dremio.tools.openapigenerator.java.JavaRestResourceGenerator;
import com.dremio.tools.openapigenerator.proto.ProtoModel;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for generating proto and java files from Open API specs. */
public final class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  @VisibleForTesting
  static class Options {
    @Parameter(
        names = {"--base_dir"},
        required = true,
        description = "Base directory for specs.")
    private String baseDir;

    @Parameter(
        names = {"--input_files"},
        required = true,
        description = "Relative paths to the open API yaml specs.")
    private List<String> inputFiles;

    @Parameter(
        names = {"--output_proto_dir"},
        required = true,
        description = "Path to the directory for output proto files.")
    private String outputProtoDir;

    @Parameter(
        names = {"--output_java_dir"},
        description = "Path to the output java directory. Package path is added to it.")
    private String outputJavaDir;

    @Parameter(
        names = {"--write_protos"},
        description = "Whether to write protos (true) or immutable java classes (false, default).")
    private boolean writeProtos;

    @Parameter(
        names = {"--help", "-h"},
        description = "Show application usage.",
        help = true)
    private boolean isHelp = false;

    Options setBaseDir(String value) {
      this.baseDir = value;
      return this;
    }

    Options setInputFiles(List<String> value) {
      this.inputFiles = value;
      return this;
    }

    Options setOutputProtoDir(String value) {
      this.outputProtoDir = value;
      return this;
    }

    Options setOutputJavaDir(String value) {
      this.outputJavaDir = value;
      return this;
    }

    Options setWriteProtos(boolean value) {
      this.writeProtos = value;
      return this;
    }
  }

  private Main() {}

  public static void main(String[] args) throws Exception {
    Options options = new Options();
    JCommander commander = new JCommander(options);
    commander.setProgramName("Open API Generator");

    try {
      commander.parse(args);
    } catch (ParameterException e) {
      commander.usage();
      throw e;
    }

    if (options.isHelp) {
      commander.usage();
    } else {
      run(options);
    }
  }

  @VisibleForTesting
  static void run(Options options) throws IOException {
    try {
      // Parse yaml specs.
      ProtoModel protoModel =
          ProtoModel.parse(options.baseDir, ImmutableSet.copyOf(options.inputFiles));

      if (options.writeProtos) {
        // Write protos.
        protoModel.writeProtos(options.outputProtoDir);
      } else {
        // Write immutables.org java interfaces.
        protoModel.writeImmutables(options.outputJavaDir);
      }

      // Write java files:
      //  - Interface is written to 'outputJavaDir' dir for packaging into a jar.
      //  - Impl is written to 'outputProtoDir' dir for manual copy to where it is intended to be
      // used.
      JavaRestResourceGenerator.generateJavaClasses(
          Clock.systemUTC(),
          options.outputJavaDir,
          options.outputProtoDir,
          protoModel,
          options.writeProtos);

      // Generate java protos with jakarta/javax constraint annotations.
      if (options.writeProtos) {
        JavaProtoGenerator.generateProtoJava(options.outputProtoDir, options.outputJavaDir);
      }
    } catch (Exception e) {
      logger.error("Generator failed with", e);
      throw e;
    }
  }
}
