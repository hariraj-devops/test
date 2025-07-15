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
package com.dremio.plugins.elastic.planning.rules;

import com.dremio.common.expression.BasePath.SchemaPathVisitor;
import com.dremio.common.expression.CompleteType;
import com.dremio.common.expression.FieldReference;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.PathSegment.ArraySegment;
import com.dremio.common.expression.PathSegment.ArraySegmentInputRef;
import com.dremio.common.expression.PathSegment.NameSegment;
import com.dremio.common.expression.SchemaPath;
import com.dremio.elastic.proto.ElasticReaderProto.ElasticSpecialType;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.record.TypedFieldId;
import com.dremio.plugins.elastic.ElasticsearchConf;
import com.dremio.plugins.elastic.ElasticsearchConstants;
import com.dremio.plugins.elastic.mapping.FieldAnnotation;
import com.dremio.plugins.elastic.planning.rels.ElasticIntermediateScanPrel;
import com.dremio.plugins.elastic.planning.rels.ElasticIntermediateScanPrel.IndexMode;
import com.dremio.service.Pointer;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.util.Pair;
import org.apache.commons.lang3.StringEscapeUtils;

/**
 * A specialized version of RexInputRef that holds an entire schema path along with important
 * information to support planning.
 */
public final class SchemaField extends RexInputRef {

  public static final Set<ElasticSpecialType> NON_DOC_TYPES =
      ImmutableSet.of(
          // you can't retrieve geo shapes using doc values
          ElasticSpecialType.GEO_SHAPE,

          // nested documents require a special nested_aggregation concept, not currently supported
          // in Dremio.
          ElasticSpecialType.NESTED);

  private static final String OPEN_BQ = "[\"";
  private static final String CLOSE_BQ = "\"]";

  private SchemaPath path;
  private final CompleteType type;
  private final FieldAnnotation annotation;
  private final ElasticSpecialType specialType;
  private final boolean isPainless;

  private SchemaField(
      int index,
      SchemaPath path,
      CompleteType type,
      boolean isNullable,
      FieldAnnotation annotation,
      RelDataTypeFactory factory,
      ElasticSpecialType specialType,
      boolean isPainless) {
    super(index, CalciteArrowHelper.wrap(type, isNullable).toCalciteType(factory, true));
    this.path = path;
    this.type = type;
    this.annotation = annotation;
    this.specialType = specialType;
    this.isPainless = isPainless;
  }

  public SchemaPath getPath() {
    return path;
  }

  public void setPath(SchemaPath path) {
    this.path = path;
  }

  public CompleteType getCompleteType() {
    return type;
  }

  public FieldAnnotation getAnnotation() {
    return annotation;
  }

  public ElasticFieldReference toReference(
      final boolean useDocIfPossible,
      final boolean sourceAvailable,
      final boolean allowPushdownAnalyzedNormalizedFields) {

    // doc references have to be scalar or scalar-list return values.
    final boolean isScalarOrScalarListReturn =
        type.isScalar() || (type.isList() && type.getOnlyChildType().isScalar());

    // some fields don't have doc values.
    final boolean hasDocValue = annotation == null || !annotation.isDocValueMissing();

    // doc values are not accurate for normalized keyword fields
    final boolean hasCorrectDocValue = annotation == null || !annotation.isNormalized();

    final boolean hasSpecialType = NON_DOC_TYPES.contains(specialType);
    final boolean useDoc =
        useDocIfPossible
            && hasDocValue
            && hasCorrectDocValue
            && isScalarOrScalarListReturn
            && !hasSpecialType;

    final StringBuilder sb = new StringBuilder();
    final StringBuilder fullPath = new StringBuilder();
    final List<Pair<String, String>> possibleNulls = new ArrayList<>();

    if (useDoc
        && !allowPushdownAnalyzedNormalizedFields
        && type.isText()
        && annotation != null
        && (annotation.isAnalyzed() || annotation.isNormalized())) {
      throw new RuntimeException(
          "Cannot pushdown because of analyzed or normalized text or keyword field.");
    }

    if (useDoc) {
      sb.append(ElasticsearchConstants.DOC);
      sb.append(OPEN_BQ);
    } else {
      if (!sourceAvailable) {
        throw new RuntimeException(
            "Cannot pushdown because neither _source nor doc value is available.");
      }
      if (isPainless) {
        // on V5, we use painless instead of groovy. In that case, source needs to be referenced
        // differently.
        sb.append(ElasticsearchConstants.SOURCE_PAINLESS);
      } else {
        sb.append(ElasticsearchConstants.SOURCE_GROOVY);
      }
    }

    final Pointer<Boolean> first = new Pointer<>(true);
    path.accept(
        new SchemaPathVisitor<Void, Void>() {

          @Override
          public Void visitName(NameSegment segment, Void in) {
            if (useDoc) {
              if (segment.isLastPath()
                  && specialType == ElasticSpecialType.GEO_POINT
                  && ("lat".equals(segment.getPath()) || "lon".equals(segment.getPath()))) {
                // since geo point is a atomic type, we need to exit the type to correctly manage
                // things.
                sb.append(CLOSE_BQ);
                possibleNulls.add(new Pair(sb.toString(), fullPath.toString()));
                sb.append('.');
                sb.append(segment.getPath());
                if (type.isList()) {
                  sb.append('s');
                }
              } else {
                if (first.value) {
                  first.value = false;
                } else {
                  sb.append(".");
                  fullPath.append(".");
                }
                String segmentPath = StringEscapeUtils.escapeJava(segment.getPath());
                sb.append(segmentPath);
                fullPath.append(segmentPath);
                if (segment.isLastPath()) {
                  sb.append(CLOSE_BQ);
                  possibleNulls.add(new Pair(sb.toString(), fullPath.toString()));
                  if (type.isList()) {
                    sb.append(".values");
                  } else {
                    sb.append(".value");
                  }
                }
              }
            } else {
              if (segment.getPath().matches("[a-zA-Z_][a-zA-Z\\d_]*")) {
                // if this field a valid field identifier, lacking special characters, use the less
                // cluttered dot syntax
                // to reference it _source.field_name
                sb.append(".");
                sb.append(segment.getPath());
              } else {
                sb.append(OPEN_BQ);
                sb.append(StringEscapeUtils.escapeJava(segment.getPath()));
                sb.append(CLOSE_BQ);
              }

              // add all parts of field as possible nulls.
              possibleNulls.add(new Pair(sb.toString(), fullPath.toString()));

              if (segment.isLastPath()) {
                switch (type.toMinorType()) {
                  case BIGINT:
                    sb.append(".longValue()");
                    break;

                  case FLOAT4:
                    sb.append(".floatValue()");
                    break;

                  case FLOAT8:
                    sb.append(".doubleValue()");
                    break;

                  case DATE:
                  case TIME:
                  case TIMESTAMPMILLI:
                    sb.append(".value");
                    break;

                  case INT:
                    sb.append(".intValue()");
                    break;

                  default:
                    break;
                }
              }
            }

            if (segment.getChild() != null) {
              return segment.getChild().accept(this, in);
            }
            return null;
          }

          @Override
          public Void visitArrayInput(ArraySegmentInputRef segment, Void in) {
            return visitArraySegment(segment.isLastPath(), segment.getPath());
          }

          @Override
          public Void visitArray(ArraySegment segment, Void in) {
            return visitArraySegment(
                segment.isLastPath(), String.valueOf(segment.getOptionalIndex()));
          }

          private Void visitArraySegment(boolean isLastPath, String indexPath) {
            if (!isLastPath) {
              throw new IllegalStateException(
                  String.format(
                      "Unable to pushdown reference %s as it includes at least one array index that is non-terminal.",
                      path.getAsUnescapedPath()));
            }

            if (useDoc) {
              sb.append(CLOSE_BQ);
              sb.append(".values");
              sb.append('[');
              sb.append(indexPath);
              sb.append(']');
              possibleNulls.add(new Pair(sb.toString(), fullPath.toString()));
            } else {
              throw new IllegalStateException(
                  String.format(
                      "Unable to pushdown reference %s as it includes at least one array index on a field that cannot be doc value accessed.",
                      path.getAsUnescapedPath()));
            }

            return null;
          }
        },
        null);

    List<NullReference> nullReferences =
        FluentIterable.from(possibleNulls)
            .transform(
                new Function<Pair<String, String>, NullReference>() {

                  @Override
                  public NullReference apply(Pair<String, String> input) {
                    return new NullReference(
                        input.getKey(),
                        input.getValue(),
                        useDoc ? ReferenceType.DOC : ReferenceType.SOURCE);
                  }
                })
            .toList();

    String ref;

    if (isPainless && type.isTemporal()) {
      ref = String.format("Instant.ofEpochMilli(%s.millis)", sb.toString());
    } else {
      ref = sb.toString();
    }

    return new ElasticFieldReference(ref, nullReferences);
  }

  public static enum ReferenceType {
    SOURCE,
    DOC
  }

  public static class ElasticFieldReference {

    private final String reference;
    private final List<NullReference> possibleNulls;

    public ElasticFieldReference(String reference, List<NullReference> possibleNulls) {
      super();
      this.reference = reference;
      this.possibleNulls = possibleNulls;
    }

    public String getReference() {
      return reference;
    }

    public List<NullReference> getPossibleNulls() {
      return possibleNulls;
    }

    @Override
    public boolean equals(final Object other) {
      if (!(other instanceof ElasticFieldReference)) {
        return false;
      }
      ElasticFieldReference castOther = (ElasticFieldReference) other;
      return Objects.equal(reference, castOther.reference)
          && Objects.equal(possibleNulls, castOther.possibleNulls);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(reference, possibleNulls);
    }
  }

  public static class NullReference {
    private final String value;
    private final String fullPath;
    private final ReferenceType referenceType;

    public NullReference(String value, String fullPath, ReferenceType referenceType) {
      super();
      this.value = value;
      this.fullPath = fullPath;
      this.referenceType = referenceType;
    }

    public String getFullPath() {
      return fullPath;
    }

    public String getValue() {
      return value;
    }

    public ReferenceType getReferenceType() {
      return referenceType;
    }

    @Override
    public boolean equals(final Object other) {
      if (!(other instanceof NullReference)) {
        return false;
      }
      NullReference castOther = (NullReference) other;
      return Objects.equal(value, castOther.value)
          && Objects.equal(referenceType, castOther.referenceType);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value, referenceType);
    }
  }

  /**
   * Given a subtree, return an updated rexnode tree that replaces item operatos and input refs with
   * a schema aware node. If conversion fails, an IllegalStateException is thrown as one or more
   * ITEM operators are expected to always be directly above input refs
   *
   * @param node Node to convert
   * @param scan Elastic scan that is below this expression.
   * @return A new node where RexInputRefs and ITEM operations are all converted to RexInputRef
   *     subclass SchemaField.
   */
  public static RexNode convert(RexNode node, ElasticIntermediateScanPrel scan) {
    return node.accept(new SchemaingShuttle(scan, ImmutableSet.<ElasticSpecialType>of()));
  }

  public static RexNode convert(
      RexNode node,
      ElasticIntermediateScanPrel scan,
      Set<ElasticSpecialType> disallowedSpecialTypes) {
    return node.accept(new SchemaingShuttle(scan, disallowedSpecialTypes));
  }

  /** Visitor that replaces item calls with a new type of RexInput */
  private static class SchemaingShuttle extends RexShuttle {

    private final Set<ElasticSpecialType> disallowedSpecialTypes;
    private final ElasticIntermediateScanPrel scan;
    private final boolean isPainless;

    public SchemaingShuttle(
        ElasticIntermediateScanPrel scan, Set<ElasticSpecialType> disallowedSpecialTypes) {
      this.scan = scan;
      this.disallowedSpecialTypes = disallowedSpecialTypes;
      ElasticsearchConf config =
          ElasticsearchConf.createElasticsearchConf(scan.getPluginId().getConnectionConf());
      this.isPainless = config.isUsePainless();
    }

    @Override
    public RexNode visitCall(RexCall call) {
      final boolean isItem =
          call.getOperator().getSyntax() == SqlSyntax.SPECIAL
              && (call.getOperator() == SqlStdOperatorTable.ITEM
                  || call.getOperator() == SqlStdOperatorTable.DOT);
      if (isItem) {
        return visitCandidate(call);
      }
      return super.visitCall(call);
    }

    private SchemaField visitCandidate(RexNode node) {
      // build a qualified path.
      SchemaPath path = node.accept(new PathVisitor(scan.getRowType(), IndexMode.ALLOW));
      Preconditions.checkArgument(path != null, "Found an incomplete item path %s.", node);

      FieldAnnotation annotation = scan.getAnnotation(path);
      ElasticSpecialType specialType = scan.getSpecialTypeRecursive(path);
      Preconditions.checkArgument(
          !disallowedSpecialTypes.contains(specialType),
          "Unable to pushdown query, %s is of type %s.",
          path.getAsUnescapedPath(),
          specialType);
      TypedFieldId fieldId = scan.getBatchSchema().getFieldId(path);
      Preconditions.checkArgument(fieldId != null, "Unable to resolve item path %s.", node);
      CompleteType type = fieldId.getFinalType();
      Field field = scan.getBatchSchema().getColumn(fieldId.getFieldIds()[0]);
      return new SchemaField(
          fieldId.getFieldIds()[0],
          path,
          type,
          field.isNullable(),
          annotation,
          scan.getCluster().getTypeFactory(),
          specialType,
          isPainless);
    }

    @Override
    public SchemaField visitInputRef(RexInputRef inputRef) {
      return visitCandidate(inputRef);
    }

    @Override
    public SchemaField visitFieldAccess(RexFieldAccess fieldAccess) {
      return visitCandidate(fieldAccess);
    }
  }

  public static class PathVisitor extends RexVisitorImpl<SchemaPath> {

    private final RelDataType inputRowType;
    private final IndexMode indexMode;

    public PathVisitor(RelDataType inputRowType, IndexMode indexMode) {
      super(true);
      this.inputRowType = inputRowType;
      this.indexMode = indexMode;
    }

    @Override
    public SchemaPath visitInputRef(RexInputRef inputRef) {
      if (inputRef instanceof SchemaField) {
        return ((SchemaField) inputRef).getPath();
      }

      final int index = inputRef.getIndex();
      final RelDataTypeField field = inputRowType.getFieldList().get(index);
      return FieldReference.getWithQuotedRef(field.getName());
    }

    @Override
    public SchemaPath visitFieldAccess(RexFieldAccess fieldAccess) {
      LogicalExpression logExpr = fieldAccess.getReferenceExpr().accept(this);
      if (!(logExpr instanceof SchemaPath)) {
        return (SchemaPath) logExpr;
      }

      SchemaPath left = (SchemaPath) logExpr;
      return left.getChild(fieldAccess.getField().getName());
    }

    @Override
    public SchemaPath visitCall(RexCall call) {
      if (call.getOperator().getSyntax() != SqlSyntax.SPECIAL
          || (call.getOperator() != SqlStdOperatorTable.ITEM
              && call.getOperator() != SqlStdOperatorTable.DOT)) {
        return null;
      }
      LogicalExpression logExpr = call.getOperands().get(0).accept(this);

      if (!(logExpr instanceof SchemaPath)) {
        return (SchemaPath) logExpr;
      }

      SchemaPath left = (SchemaPath) logExpr;
      final RexLiteral literal = (RexLiteral) call.getOperands().get(1);
      switch (literal.getTypeName()) {
        case DECIMAL:
        case INTEGER:
          switch (indexMode) {
            case ALLOW:
              return left.getChild(((BigDecimal) literal.getValue()).intValue());
            case SKIP:
              return left;
            case DISALLOW:
            default:
              return null;
          }

        case CHAR:
        case VARCHAR:
          return left.getChild(literal.getValue2().toString());
        default:
          // fall through
      }

      return null;
    }
  }
}
