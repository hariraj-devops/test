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
package com.dremio.common.expression;

import com.dremio.common.expression.PathSegment.ArraySegment;
import com.dremio.common.expression.PathSegment.ArraySegmentInputRef;
import com.dremio.common.expression.PathSegment.NameSegment;
import com.dremio.common.expression.PathSegment.PathSegmentType;
import com.dremio.common.expression.parser.ExprLexer;
import com.dremio.common.expression.parser.ExprParser;
import com.dremio.common.expression.parser.ExprParser.parse_return;
import com.dremio.common.expression.visitors.ExprVisitor;
import com.dremio.common.util.SchemaPathDeserializer;
import com.dremio.exec.proto.UserBitShared.NamePart;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.google.common.base.Preconditions;
import java.io.IOException;
import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.jetbrains.annotations.Nullable;

@JsonDeserialize(using = SchemaPathDeserializer.class)
public class SchemaPath extends BasePath implements LogicalExpression, Comparable<SchemaPath> {
  // Unused but required for Kryo deserialization as it used to exist pre 4.2.
  @Deprecated private EvaluationType evaluationType;

  public SchemaPath(SchemaPath path) {
    super(path.rootSegment);
  }

  public SchemaPath(NameSegment rootSegment) {
    super(rootSegment);
  }

  @Override
  public PathSegment getLastSegment() {
    PathSegment s = rootSegment;
    while (s.getChild() != null) {
      s = s.getChild();
    }
    return s;
  }

  @Deprecated
  public SchemaPath(String simpleName) {
    super(new NameSegment(simpleName));
    if (simpleName.contains(".")) {
      throw new IllegalStateException("This is deprecated and only supports simple paths.");
    }
  }

  /**
   * A simple is a path where there are no repeated elements outside the lowest level of the path.
   *
   * @return Whether this path is a simple path.
   */
  @Override
  public boolean isSimplePath() {
    PathSegment seg = rootSegment;
    while (seg != null) {
      if ((seg.getType().equals(PathSegmentType.ARRAY_INDEX)
              || seg.getType().equals(PathSegmentType.ARRAY_INDEX_REF))
          && !seg.isLastPath()) {
        return false;
      }
      seg = seg.getChild();
    }
    return true;
  }

  @Override
  public <T, V, E extends Exception> T accept(ExprVisitor<T, V, E> visitor, V value) throws E {
    return visitor.visitSchemaPath(this, value);
  }

  public SchemaPath getChild(String childPath) {
    NameSegment newRoot = rootSegment.cloneWithNewChild(new NameSegment(childPath));
    return new SchemaPath(newRoot);
  }

  public SchemaPath getChild(String childPath, boolean isIndexInputRef) {
    NameSegment newRoot;
    if (isIndexInputRef) {
      newRoot = rootSegment.cloneWithNewChild(new ArraySegmentInputRef(childPath));
    } else {
      newRoot = rootSegment.cloneWithNewChild(new NameSegment(childPath));
    }
    return new SchemaPath(newRoot);
  }

  public SchemaPath getParent() {
    if (rootSegment.isLastPath()) {
      return null;
    }
    return new SchemaPath(rootSegment.cloneWithoutChild());
  }

  public SchemaPath getChild(int index) {
    NameSegment newRoot = rootSegment.cloneWithNewChild(new ArraySegment(index));
    return new SchemaPath(newRoot);
  }

  @Override
  public CompleteType getCompleteType() {
    return CompleteType.LATE;
  }

  @Override
  public int hashCode() {
    return ((rootSegment == null) ? 0 : rootSegment.hashCode());
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (!(obj instanceof SchemaPath)) {
      return false;
    }

    SchemaPath other = (SchemaPath) obj;
    if (rootSegment == null) {
      return (other.rootSegment == null);
    }
    return rootSegment.equals(other.rootSegment);
  }

  public boolean contains(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (!(obj instanceof SchemaPath)) {
      return false;
    }

    SchemaPath other = (SchemaPath) obj;
    return rootSegment == null || rootSegment.contains(other.rootSegment);
  }

  @Override
  public String toString() {
    return ExpressionStringBuilder.toString(this);
  }

  public String toExpr() {
    return ExpressionStringBuilder.toString(this);
  }

  public static class De extends StdDeserializer<SchemaPath> {

    public De() {
      super(LogicalExpression.class);
    }

    @Override
    public SchemaPath deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {
      String expr = jp.getText();
      return deserializeImpl(expr);
    }

    @Nullable
    public static SchemaPath deserializeImpl(String expr) {
      if (expr == null || expr.isEmpty()) {
        return null;
      }
      try {
        // logger.debug("Parsing expression string '{}'", expr);
        ExprLexer lexer = new ExprLexer(new ANTLRStringStream(expr));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        ExprParser parser = new ExprParser(tokens);

        // TODO: move functionregistry and error collector to injectables.
        // ctxt.findInjectableValue(valueId, forProperty, beanInstance)
        parse_return ret = parser.parse();

        // ret.e.resolveAndValidate(expr, errorCollector);
        if (ret.e instanceof SchemaPath) {
          return (SchemaPath) ret.e;
        } else {
          throw new IllegalStateException("Schema path is not a valid format.");
        }
      } catch (RecognitionException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public int compareTo(SchemaPath o) {
    return this.getAsUnescapedPath().compareTo(o.getAsUnescapedPath());
  }

  @Override
  @JsonIgnore
  public int getSelfCost() {
    return 0;
  }

  @JsonIgnore
  @Override
  public int getCumulativeCost() {
    return 0;
  }

  public String toDotString() {
    StringBuilder pathValue = new StringBuilder();
    pathValue.append(rootSegment.getNameSegment().getPath());
    PathSegment seg = rootSegment.getChild();
    while (seg != null) {
      if (seg.getType().equals(PathSegmentType.ARRAY_INDEX)) {
        pathValue.append(".list.element");
      } else {
        pathValue.append(".").append(seg.getNameSegment().getPath());
      }
      seg = seg.getChild();
    }
    return pathValue.toString();
  }

  public static SchemaPath create(NamePart namePart) {
    Preconditions.checkArgument(namePart.getType() == NamePart.Type.NAME);
    return new SchemaPath((NameSegment) getPathSegment(namePart));
  }

  public static SchemaPath getSimplePath(String name) {
    return getCompoundPath(name);
  }

  public static SchemaPath getCompoundPath(String... strings) {
    NameSegment s = null;
    // loop through strings in reverse order
    for (int i = strings.length - 1; i >= 0; i--) {
      s = new NameSegment(strings[i], s);
    }
    return new SchemaPath(s);
  }
}
