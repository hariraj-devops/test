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
package com.dremio.sabot.op.writer;

import com.dremio.common.AutoCloseables;
import com.dremio.common.expression.FieldReference;
import com.dremio.common.expression.FunctionCall;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.expression.ValueExpressions;
import com.dremio.common.logical.data.NamedExpression;
import com.dremio.exec.physical.config.Project;
import com.dremio.exec.physical.config.WriterCommitterPOP;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.store.RecordWriter;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.project.ProjectOperator;
import com.dremio.sabot.op.spi.SingleInputOperator;
import com.google.common.collect.ImmutableList;
import java.util.regex.Pattern;

/** This strategy projects all incoming records to outgoing vectors */
public class ProjectOutputHandler implements WriterCommitterOutputHandler {
  private final WriterCommitterPOP config;
  private final OperatorContext context;
  private ProjectOperator project;

  public ProjectOutputHandler(OperatorContext context, WriterCommitterPOP config) {
    this.config = config;
    this.context = context;
  }

  @Override
  public VectorAccessible setup(VectorAccessible accessible) throws Exception {
    // replacement expression.
    LogicalExpression replacement;
    if (config.getTempLocation() != null) {
      replacement =
          new FunctionCall(
              "REGEXP_REPLACE",
              ImmutableList.of(
                  SchemaPath.getSimplePath(RecordWriter.PATH.getName()),
                  new ValueExpressions.QuotedString(Pattern.quote(config.getTempLocation())),
                  new ValueExpressions.QuotedString(config.getFinalLocation())));
    } else {
      replacement = SchemaPath.getSimplePath(RecordWriter.PATH.getName());
    }
    ImmutableList<NamedExpression> namedExpressions =
        ImmutableList.of(
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.FRAGMENT.getName()),
                new FieldReference(RecordWriter.FRAGMENT.getName())),
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.RECORDS.getName()),
                new FieldReference(RecordWriter.RECORDS.getName())),
            new NamedExpression(replacement, new FieldReference(RecordWriter.PATH.getName())),
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.METADATA.getName()),
                new FieldReference(RecordWriter.METADATA.getName())),
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.PARTITION.getName()),
                new FieldReference(RecordWriter.PARTITION.getName())),
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.FILESIZE.getName()),
                new FieldReference(RecordWriter.FILESIZE.getName())),
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.ICEBERG_METADATA.getName()),
                new FieldReference(RecordWriter.ICEBERG_METADATA.getName())),
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.FILE_SCHEMA.getName()),
                new FieldReference(RecordWriter.FILE_SCHEMA.getName())),
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.PARTITION_DATA.getName()),
                new FieldReference(RecordWriter.PARTITION_DATA.getName())),
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.OPERATION_TYPE.getName()),
                new FieldReference(RecordWriter.OPERATION_TYPE.getName())),
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.PARTITION_VALUE.getName()),
                new FieldReference(RecordWriter.PARTITION_VALUE.getName())),
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.REJECTED_RECORDS.getName()),
                new FieldReference(RecordWriter.REJECTED_RECORDS.getName())),
            new NamedExpression(
                SchemaPath.getSimplePath(RecordWriter.REFERENCED_DATA_FILES.getName()),
                new FieldReference(RecordWriter.REFERENCED_DATA_FILES.getName())));

    assert namedExpressions.size() == RecordWriter.SCHEMA.getFields().size()
        : "Named expressions schema doesn't match RecordWriter Schema";

    Project projectConfig = new Project(config.getProps(), null, namedExpressions);
    this.project = new ProjectOperator(context, projectConfig);
    return project.setup(accessible);
  }

  @Override
  public SingleInputOperator.State getState() {
    return project == null ? SingleInputOperator.State.NEEDS_SETUP : project.getState();
  }

  @Override
  public int outputData() throws Exception {
    return project.outputData();
  }

  @Override
  public void noMoreToConsume() throws Exception {
    project.noMoreToConsume();
  }

  @Override
  public void consumeData(int records) throws Exception {
    project.consumeData(records);
  }

  @Override
  public void write(WriterCommitterRecord rec) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(project);
  }
}
