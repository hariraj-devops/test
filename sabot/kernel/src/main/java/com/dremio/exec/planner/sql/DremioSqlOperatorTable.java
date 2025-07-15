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
package com.dremio.exec.planner.sql;

import static org.apache.calcite.sql.type.ReturnTypes.cascade;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.expr.fn.hll.HyperLogLog;
import com.dremio.exec.expr.fn.impl.MapFunctions;
import com.dremio.exec.expr.fn.listagg.ListAgg;
import com.dremio.exec.expr.fn.tdigest.TDigest;
import com.dremio.exec.planner.logical.RelDataTypeEqualityComparer;
import com.dremio.exec.planner.sql.parser.SqlContains;
import com.dremio.exec.planner.types.JavaTypeFactoryImpl;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.fun.SqlBaseContextVariable;
import org.apache.calcite.sql.fun.SqlBasicAggFunction;
import org.apache.calcite.sql.fun.SqlCastFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeTransforms;
import org.apache.calcite.sql.util.ReflectiveSqlOperatorTable;
import org.apache.calcite.util.Optionality;

/**
 * Dremio's Sql Operator Table
 *
 * <p>These operators are used to resolve {@link org.apache.calcite.sql.SqlJdbcFunctionCall} before
 * checking {@link org.apache.calcite.sql.fun.SqlStdOperatorTable}.
 */
public class DremioSqlOperatorTable extends ReflectiveSqlOperatorTable {
  private static DremioSqlOperatorTable instance;

  // ---------------------
  // HyperLogLog Functions
  // ---------------------

  public static final SqlAggFunction HLL = new HyperLogLog.SqlHllAggFunction();
  public static final SqlAggFunction HLL_MERGE = new HyperLogLog.SqlHllMergeAggFunction();
  public static final SqlAggFunction NDV = new HyperLogLog.SqlNdvAggFunction();
  public static final SqlFunction HLL_DECODE = new HyperLogLog.SqlHllDecodeOperator();

  // ---------------------
  // ListAgg Functions
  // ---------------------
  public static final SqlAggFunction LISTAGG_MERGE = new ListAgg.SqlListAggMergeFunction();
  public static final SqlAggFunction LOCAL_LISTAGG = new ListAgg.SqlLocalListAggFunction();

  // ---------------
  // GEO functions
  // ---------------
  public static final SqlOperator GEO_DISTANCE =
      SqlOperatorBuilder.name("GEO_DISTANCE")
          .returnType(ReturnTypes.DOUBLE)
          .operandTypes(SqlOperands.FLOAT, SqlOperands.FLOAT, SqlOperands.FLOAT, SqlOperands.FLOAT)
          .build();

  public static final SqlOperator GEO_NEARBY =
      SqlOperatorBuilder.name("GEO_NEARBY")
          .returnType(ReturnTypes.BOOLEAN)
          .operandTypes(
              SqlOperands.FLOAT,
              SqlOperands.FLOAT,
              SqlOperands.FLOAT,
              SqlOperands.FLOAT,
              SqlOperands.NUMERIC_TYPES)
          .build();

  public static final SqlOperator GEO_BEYOND =
      SqlOperatorBuilder.name("GEO_BEYOND")
          .returnType(ReturnTypes.BOOLEAN)
          .operandTypes(
              SqlOperands.FLOAT,
              SqlOperands.FLOAT,
              SqlOperands.FLOAT,
              SqlOperands.FLOAT,
              SqlOperands.NUMERIC_TYPES)
          .build();

  // ---------------------
  // STD Library Functions
  // ---------------------

  // Overloading CAST function to support VARIANT type.
  public static final SqlOperator CAST =
      new SqlCastFunction() {
        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
          SqlNode left = callBinding.operand(0);
          RelDataType validatedNodeType = callBinding.getValidator().getValidatedNodeType(left);

          boolean isVariant =
              (validatedNodeType == VariantNotNullRelDataType.INSTANCE)
                  || (validatedNodeType == VariantNullableRelDataType.INSTANCE);
          if (isVariant) {
            return true;
          }

          return super.checkOperandTypes(callBinding, throwOnFailure);
        }
      };

  public static final SqlOperator ROUND =
      SqlOperatorBuilder.name("ROUND")
          .returnType(DremioReturnTypes.NULLABLE_ROUND)
          .operandTypes(OperandTypes.NUMERIC_OPTIONAL_INTEGER)
          .build();

  public static final SqlOperator LOG2 =
      SqlOperatorBuilder.name("LOG2")
          .returnType(ReturnTypes.DOUBLE)
          .operandTypes(SqlOperands.NUMERIC_TYPES)
          .build();

  public static final SqlOperator LOG =
      SqlOperatorBuilder.name("LOG")
          .returnType(ReturnTypes.DOUBLE)
          .operandTypes(SqlOperands.NUMERIC_TYPES, SqlOperands.NUMERIC_TYPES)
          .build();

  public static final SqlOperator TRUNCATE =
      SqlOperatorBuilder.name("TRUNCATE")
          .returnType(DremioReturnTypes.NULLABLE_TRUNCATE)
          .operandTypes(OperandTypes.NUMERIC_OPTIONAL_INTEGER)
          .build();

  public static final SqlOperator TRUNC = SqlOperatorBuilder.alias("TRUNC", TRUNCATE);

  public static final SqlOperator TYPEOF =
      SqlOperatorBuilder.name("TYPEOF")
          .returnType(SqlTypeName.VARCHAR)
          .operandTypes(SqlOperands.ANY)
          .build();

  public static final SqlOperator IDENTITY =
      SqlOperatorBuilder.name("IDENTITY")
          .returnType(ReturnTypes.ARG0)
          .operandTypes(SqlOperands.ANY)
          .build();

  // -----------------------
  // Overriding Default Behavior
  // -----------------------

  // REGEX_SPLIT returns an array of VARCHAR instead of ANY
  public static final SqlOperator REGEXP_SPLIT =
      SqlOperatorBuilder.name("REGEXP_SPLIT")
          .returnType(
              JavaTypeFactoryImpl.INSTANCE.createArrayType(
                  JavaTypeFactoryImpl.INSTANCE.createSqlType(SqlTypeName.VARCHAR), -1))
          .operandTypes(
              SqlOperands.CHAR_TYPES,
              SqlOperands.CHAR_TYPES,
              SqlOperands.CHAR_TYPES,
              SqlOperands.EXACT_TYPES)
          .build();

  // SqlStdOperatorTable.CARDINALITY is overridden here because
  // it supports LIST, MAP as well as STRUCT. In Dremio, we want to
  // allow only LIST and MAP. Not STRUCT.
  public static final SqlOperator CARDINALITY =
      SqlOperatorBuilder.name("CARDINALITY")
          .returnType(ReturnTypes.INTEGER_NULLABLE)
          .operandTypes(UnionedSqlOperand.create(SqlOperands.ARRAY, SqlOperands.MAP))
          .build();

  // Concat needs to support taking a variable length argument and honor a nullability check
  public static final SqlOperator CONCAT =
      SqlOperatorBuilder.name("CONCAT")
          .returnType(DremioReturnTypes.VARCHAR_MAX_PRECISION_NULLABLE)
          .operandTypes(SqlOperands.ANY, SqlOperands.ANY_VARIADIC)
          .build();

  // Calcite's REPLACE type inference does not honor the precision
  public static final SqlOperator REPLACE =
      SqlOperatorBuilder.name("REPLACE")
          .returnType(DremioReturnTypes.VARCHAR_MAX_PRECISION_NULLABLE)
          .operandTypes(SqlOperands.ANY, SqlOperands.ANY, SqlOperands.ANY)
          .build();

  // We want to allow for an optional encoding format in the LENGTH function
  public static final SqlOperator LENGTH =
      SqlOperatorBuilder.name("LENGTH")
          .returnType(ReturnTypes.INTEGER_NULLABLE)
          .operandTypes(SqlOperands.ANY, SqlOperands.ANY)
          .build();

  // Trim and Pad functions need to honor nullability
  public static final SqlOperator LPAD =
      SqlOperatorBuilder.name("LPAD")
          .returnType(DremioReturnTypes.VARCHAR_MAX_PRECISION_NULLABLE)
          .operandTypes(SqlOperands.ANY, SqlOperands.ANY, SqlOperands.ANY)
          .build();

  public static final SqlOperator RPAD =
      SqlOperatorBuilder.name("RPAD")
          .returnType(DremioReturnTypes.VARCHAR_MAX_PRECISION_NULLABLE)
          .operandTypes(SqlOperands.ANY, SqlOperands.ANY, SqlOperands.ANY)
          .build();

  public static final SqlOperator LTRIM =
      SqlOperatorBuilder.name("LTRIM")
          .returnType(DremioReturnTypes.VARCHAR_MAX_PRECISION_NULLABLE)
          .operandTypes(SqlOperands.ANY, SqlOperands.ANY_OPTIONAL)
          .build();

  public static final SqlOperator RTRIM =
      SqlOperatorBuilder.name("RTRIM")
          .returnType(DremioReturnTypes.VARCHAR_MAX_PRECISION_NULLABLE)
          .operandTypes(SqlOperands.ANY, SqlOperands.ANY_OPTIONAL)
          .build();

  public static final SqlOperator BTRIM =
      SqlOperatorBuilder.name("BTRIM")
          .returnType(DremioReturnTypes.VARCHAR_MAX_PRECISION_NULLABLE)
          .operandTypes(SqlOperands.ANY, SqlOperands.ANY_OPTIONAL)
          .build();

  public static final SqlOperator TRIM =
      SqlOperatorBuilder.name("TRIM")
          .returnType(DremioReturnTypes.VARCHAR_MAX_PRECISION_NULLABLE)
          .operandTypes(SqlOperands.ANY, SqlOperands.ANY_OPTIONAL)
          .build();

  public static final SqlOperator REPEAT =
      SqlOperatorBuilder.name("REPEAT")
          .returnType(DremioReturnTypes.VARCHAR_MAX_PRECISION_NULLABLE)
          .operandTypes(SqlOperands.VARCHAR, SqlOperands.INTEGER)
          .build();

  public static final SqlOperator SPACE =
      SqlOperatorBuilder.name("SPACE")
          .returnType(DremioReturnTypes.VARCHAR_MAX_PRECISION_NULLABLE)
          .operandTypes(SqlOperands.INTEGER)
          .build();

  public static final SqlOperator SECOND =
      SqlOperatorBuilder.name("SECOND")
          .returnType(ReturnTypes.BIGINT_NULLABLE)
          .operandTypes(
              OperandTypes.or(
                  OperandTypes.family(SqlTypeFamily.DATE),
                  OperandTypes.family(SqlTypeFamily.TIMESTAMP),
                  OperandTypes.family(SqlTypeFamily.DATETIME),
                  OperandTypes.family(SqlTypeFamily.DATETIME_INTERVAL),
                  OperandTypes.family(SqlTypeFamily.INTERVAL_DAY_TIME)))
          .build();

  public static final SqlOperator MINUTE =
      SqlOperatorBuilder.name("MINUTE")
          .returnType(ReturnTypes.BIGINT_NULLABLE)
          .operandTypes(
              OperandTypes.or(
                  OperandTypes.family(SqlTypeFamily.DATE),
                  OperandTypes.family(SqlTypeFamily.TIMESTAMP),
                  OperandTypes.family(SqlTypeFamily.DATETIME),
                  OperandTypes.family(SqlTypeFamily.DATETIME_INTERVAL),
                  OperandTypes.family(SqlTypeFamily.INTERVAL_DAY_TIME)))
          .build();

  public static final SqlOperator HOUR =
      SqlOperatorBuilder.name("HOUR")
          .returnType(ReturnTypes.BIGINT_NULLABLE)
          .operandTypes(
              OperandTypes.or(
                  OperandTypes.family(SqlTypeFamily.DATE),
                  OperandTypes.family(SqlTypeFamily.TIMESTAMP),
                  OperandTypes.family(SqlTypeFamily.DATETIME),
                  OperandTypes.family(SqlTypeFamily.DATETIME_INTERVAL),
                  OperandTypes.family(SqlTypeFamily.INTERVAL_DAY_TIME)))
          .build();

  public static final SqlOperator DAY =
      SqlOperatorBuilder.name("DAY")
          .returnType(ReturnTypes.BIGINT_NULLABLE)
          .operandTypes(
              OperandTypes.or(
                  OperandTypes.family(SqlTypeFamily.DATE),
                  OperandTypes.family(SqlTypeFamily.TIMESTAMP),
                  OperandTypes.family(SqlTypeFamily.DATETIME),
                  OperandTypes.family(SqlTypeFamily.DATETIME_INTERVAL),
                  OperandTypes.family(SqlTypeFamily.INTERVAL_DAY_TIME),
                  OperandTypes.family(SqlTypeFamily.INTERVAL_YEAR_MONTH)))
          .build();

  public static final SqlOperator DAYOFMONTH =
      SqlOperatorBuilder.name("DAYOFMONTH")
          .returnType(ReturnTypes.BIGINT_NULLABLE)
          .operandTypes(
              OperandTypes.or(
                  OperandTypes.family(SqlTypeFamily.DATE),
                  OperandTypes.family(SqlTypeFamily.TIMESTAMP),
                  OperandTypes.family(SqlTypeFamily.DATETIME),
                  OperandTypes.family(SqlTypeFamily.DATETIME_INTERVAL),
                  OperandTypes.family(SqlTypeFamily.INTERVAL_DAY_TIME),
                  OperandTypes.family(SqlTypeFamily.INTERVAL_YEAR_MONTH)))
          .build();

  public static final SqlOperator MONTH =
      SqlOperatorBuilder.name("MONTH")
          .returnType(ReturnTypes.BIGINT_NULLABLE)
          .operandTypes(
              OperandTypes.or(
                  OperandTypes.family(SqlTypeFamily.DATE),
                  OperandTypes.family(SqlTypeFamily.TIMESTAMP),
                  OperandTypes.family(SqlTypeFamily.DATETIME),
                  OperandTypes.family(SqlTypeFamily.DATETIME_INTERVAL),
                  OperandTypes.family(SqlTypeFamily.INTERVAL_DAY_TIME),
                  OperandTypes.family(SqlTypeFamily.INTERVAL_YEAR_MONTH)))
          .build();

  public static final SqlOperator YEAR =
      SqlOperatorBuilder.name("YEAR")
          .returnType(ReturnTypes.BIGINT_NULLABLE)
          .operandTypes(
              OperandTypes.or(
                  OperandTypes.family(SqlTypeFamily.DATE),
                  OperandTypes.family(SqlTypeFamily.TIMESTAMP),
                  OperandTypes.family(SqlTypeFamily.DATETIME),
                  OperandTypes.family(SqlTypeFamily.DATETIME_INTERVAL),
                  OperandTypes.family(SqlTypeFamily.INTERVAL_DAY_TIME),
                  OperandTypes.family(SqlTypeFamily.INTERVAL_YEAR_MONTH)))
          .build();

  // -----------------------
  // Dremio Custom Functions
  // -----------------------
  public static final SqlOperator CONVERT_FROM = ConvertFromOperators.CONVERT_FROM;

  public static final SqlOperator CONVERT_FROMBIGINT = ConvertFromOperators.CONVERT_FROMBIGINT;

  public static final SqlOperator CONVERT_FROMBIGINT_BE =
      ConvertFromOperators.CONVERT_FROMBIGINT_BE;

  public static final SqlOperator CONVERT_FROMBIGINT_HADOOPV =
      ConvertFromOperators.CONVERT_FROMBIGINT_HADOOPV;

  public static final SqlOperator CONVERT_FROMBOOLEAN_BYTE =
      ConvertFromOperators.CONVERT_FROMBOOLEAN_BYTE;

  public static final SqlOperator CONVERT_FROMDATE_EPOCH =
      ConvertFromOperators.CONVERT_FROMDATE_EPOCH;

  public static final SqlOperator CONVERT_FROMDATE_EPOCH_BE =
      ConvertFromOperators.CONVERT_FROMDATE_EPOCH_BE;

  public static final SqlOperator CONVERT_FROMDOUBLE = ConvertFromOperators.CONVERT_FROMDOUBLE;

  public static final SqlOperator CONVERT_FROMDOUBLE_BE =
      ConvertFromOperators.CONVERT_FROMDOUBLE_BE;

  public static final SqlOperator CONVERT_FROMFLOAT = ConvertFromOperators.CONVERT_FROMFLOAT;

  public static final SqlOperator CONVERT_FROMFLOAT_BE = ConvertFromOperators.CONVERT_FROMFLOAT_BE;

  public static final SqlOperator CONVERT_FROMINT = ConvertFromOperators.CONVERT_FROMINT;

  public static final SqlOperator CONVERT_FROMINT_BE = ConvertFromOperators.CONVERT_FROMINT_BE;

  public static final SqlOperator CONVERT_FROMINT_HADOOPV =
      ConvertFromOperators.CONVERT_FROMINT_HADOOPV;

  public static final SqlOperator CONVERT_FROMJSON = ConvertFromOperators.CONVERT_FROMJSON;

  public static final SqlOperator CONVERT_FROMTIMESTAMP_EPOCH =
      ConvertFromOperators.CONVERT_FROMTIMESTAMP_EPOCH;

  public static final SqlOperator CONVERT_FROMTIMESTAMP_EPOCH_BE =
      ConvertFromOperators.CONVERT_FROMTIMESTAMP_EPOCH_BE;

  public static final SqlOperator CONVERT_FROMTIMESTAMP_IMPALA =
      ConvertFromOperators.CONVERT_FROMTIMESTAMP_IMPALA;

  public static final SqlOperator CONVERT_FROMTIMESTAMP_IMPALA_LOCALTIMEZONE =
      ConvertFromOperators.CONVERT_FROMTIMESTAMP_IMPALA_LOCALTIMEZONE;

  public static final SqlOperator CONVERT_FROMTIME_EPOCH =
      ConvertFromOperators.CONVERT_FROMTIME_EPOCH;

  public static final SqlOperator CONVERT_FROMTIME_EPOCH_BE =
      ConvertFromOperators.CONVERT_FROMTIME_EPOCH_BE;

  public static final SqlOperator CONVERT_FROMUTF8 = ConvertFromOperators.CONVERT_FROMUTF8;

  public static final SqlOperator CONVERT_REPLACEUTF8 = ConvertFromOperators.CONVERT_REPLACEUTF8;

  public static final SqlOperator TRY_CONVERT_FROM = new SqlTryConvertFromFunction();

  public static final SqlOperator CONVERT_TO = ConvertToOperators.CONVERT_TO;
  public static final SqlOperator CONVERT_TOBASE64 = ConvertToOperators.CONVERT_TOBASE64;
  public static final SqlOperator CONVERT_TOBIGINT = ConvertToOperators.CONVERT_TOBIGINT;
  public static final SqlOperator CONVERT_TOBIGINT_BE = ConvertToOperators.CONVERT_TOBIGINT_BE;
  public static final SqlOperator CONVERT_TOBIGINT_HADOOPV =
      ConvertToOperators.CONVERT_TOBIGINT_HADOOPV;
  public static final SqlOperator CONVERT_TOBOOLEAN_BYTE =
      ConvertToOperators.CONVERT_TOBOOLEAN_BYTE;
  public static final SqlOperator CONVERT_TOCOMPACTJSON = ConvertToOperators.CONVERT_TOCOMPACTJSON;
  public static final SqlOperator CONVERT_TODATE_EPOCH = ConvertToOperators.CONVERT_TODATE_EPOCH;
  public static final SqlOperator CONVERT_TODATE_EPOCH_BE =
      ConvertToOperators.CONVERT_TODATE_EPOCH_BE;
  public static final SqlOperator CONVERT_TODOUBLE = ConvertToOperators.CONVERT_TODOUBLE;
  public static final SqlOperator CONVERT_TODOUBLE_BE = ConvertToOperators.CONVERT_TODOUBLE_BE;
  public static final SqlOperator CONVERT_TOEXTENDEDJSON =
      ConvertToOperators.CONVERT_TOEXTENDEDJSON;
  public static final SqlOperator CONVERT_TOFLOAT = ConvertToOperators.CONVERT_TOFLOAT;
  public static final SqlOperator CONVERT_TOFLOAT_BE = ConvertToOperators.CONVERT_TOFLOAT_BE;
  public static final SqlOperator CONVERT_TOINT = ConvertToOperators.CONVERT_TOINT;
  public static final SqlOperator CONVERT_TOINT_BE = ConvertToOperators.CONVERT_TOINT_BE;
  public static final SqlOperator CONVERT_TOINT_HADOOPV = ConvertToOperators.CONVERT_TOINT_HADOOPV;
  public static final SqlOperator CONVERT_TOJSON = ConvertToOperators.CONVERT_TOJSON;
  public static final SqlOperator CONVERT_TOSIMPLEJSON = ConvertToOperators.CONVERT_TOSIMPLEJSON;
  public static final SqlOperator CONVERT_TOTIMESTAMP_EPOCH =
      ConvertToOperators.CONVERT_TOTIMESTAMP_EPOCH;
  public static final SqlOperator CONVERT_TOTIMESTAMP_EPOCH_BE =
      ConvertToOperators.CONVERT_TOTIMESTAMP_EPOCH_BE;
  public static final SqlOperator CONVERT_TOTIME_EPOCH = ConvertToOperators.CONVERT_TOTIME_EPOCH;
  public static final SqlOperator CONVERT_TOTIME_EPOCH_BE =
      ConvertToOperators.CONVERT_TOTIME_EPOCH_BE;
  public static final SqlOperator CONVERT_TOUTF8 = ConvertToOperators.CONVERT_TOUTF8;

  public static final SqlFunction FLATTEN = new SqlFlattenOperator(0);
  public static final SqlFunction DATE_PART = new SqlDatePartOperator();

  // Function for E()
  public static final SqlOperator E =
      SqlOperatorBuilder.name("E").returnType(SqlTypeName.DOUBLE).noOperands().build();

  public static final SqlFunction HASH =
      new SqlFunction(
          "HASH",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.explicit(SqlTypeName.INTEGER),
          null,
          OperandTypes.ANY,
          SqlFunctionCategory.USER_DEFINED_FUNCTION);

  /**
   * The MEDIAN operator. Takes the median / PERCENTILE_CONT(.5) of a dataset. The argument must be
   * a numeric expression. The return type is a double.
   */
  public static final SqlAggFunction MEDIAN =
      SqlBasicAggFunction.create("MEDIAN", SqlKind.OTHER, ReturnTypes.DOUBLE, OperandTypes.NUMERIC)
          .withFunctionType(SqlFunctionCategory.SYSTEM);

  public static final VarArgSqlOperator CONTAINS_OPERATOR =
      new VarArgSqlOperator("contains", SqlContains.RETURN_TYPE, true);

  public static final SqlFunction COL_LIKE =
      new SqlFunction(
          "COL_LIKE",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.BOOLEAN_NULLABLE,
          null,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.USER_DEFINED_FUNCTION);

  public static final SqlFunction REGEXP_LIKE =
      new SqlFunction(
          "REGEXP_LIKE",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.BOOLEAN_NULLABLE,
          null,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.USER_DEFINED_FUNCTION);

  public static final SqlFunction REGEXP_COL_LIKE =
      new SqlFunction(
          "REGEXP_COL_LIKE",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.BOOLEAN_NULLABLE,
          null,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.USER_DEFINED_FUNCTION);

  // -----------------------------
  // Dremio Hive Masking Functions
  // -----------------------------
  private static final SqlReturnTypeInference HIVE_RETURN_TYPE_INFERENCE =
      opBinding -> {
        RelDataType type = opBinding.getOperandType(0);
        if ((type.getFamily() == SqlTypeFamily.CHARACTER)
            || (type.getSqlTypeName() == SqlTypeName.DATE)
            || (SqlTypeName.INT_TYPES.contains(type.getSqlTypeName()))) {
          return type;
        }

        return opBinding.getTypeFactory().createTypeWithNullability(type, true);
      };

  public static final SqlOperator HIVE_MASK_INTERNAL =
      SqlOperatorBuilder.name("MASK_INTERNAL")
          .returnType(HIVE_RETURN_TYPE_INFERENCE)
          .operandTypes(OperandTypes.ONE_OR_MORE)
          .build();

  public static final SqlOperator HIVE_MASK =
      SqlOperatorBuilder.name("MASK")
          .returnType(HIVE_RETURN_TYPE_INFERENCE)
          .operandTypes(OperandTypes.ONE_OR_MORE)
          .build();

  public static final SqlOperator HIVE_MASK_FIRST_N =
      SqlOperatorBuilder.name("MASK_FIRST_N")
          .returnType(HIVE_RETURN_TYPE_INFERENCE)
          .operandTypes(OperandTypes.ONE_OR_MORE)
          .build();

  public static final SqlOperator HIVE_MASK_LAST_N =
      SqlOperatorBuilder.name("MASK_LAST_N")
          .returnType(HIVE_RETURN_TYPE_INFERENCE)
          .operandTypes(OperandTypes.ONE_OR_MORE)
          .build();
  public static final SqlOperator HIVE_MASK_SHOW_FIRST_N =
      SqlOperatorBuilder.name("MASK_SHOW_FIRST_N")
          .returnType(HIVE_RETURN_TYPE_INFERENCE)
          .operandTypes(OperandTypes.ONE_OR_MORE)
          .build();

  public static final SqlOperator HIVE_MASK_SHOW_LAST_N =
      SqlOperatorBuilder.name("MASK_SHOW_LAST_N")
          .returnType(HIVE_RETURN_TYPE_INFERENCE)
          .operandTypes(OperandTypes.ONE_OR_MORE)
          .build();

  public static final SqlOperator HIVE_MASK_HASH =
      SqlOperatorBuilder.name("MASK_HASH")
          .returnType(
              opBinding -> {
                RelDataType type = opBinding.getOperandType(0);
                if (type.getFamily() == SqlTypeFamily.CHARACTER) {
                  return opBinding.getTypeFactory().createSqlType(SqlTypeName.VARCHAR);
                }

                return opBinding.getTypeFactory().createTypeWithNullability(type, true);
              })
          .operandTypes(OperandTypes.ONE_OR_MORE)
          .build();

  // ------------------------
  // Dremio Gandiva Functions
  // ------------------------
  public static final SqlOperator HASHSHA256 =
      SqlOperatorBuilder.name("HASHSHA256").returnType(SqlTypeName.VARCHAR).anyOperands().build();

  public static final SqlOperator BITWISE_AND =
      SqlOperatorBuilder.name("BITWISE_AND")
          .returnType(ReturnTypes.ARG1)
          .operandTypes(OperandTypes.NUMERIC_INTEGER)
          .build();

  // ---------------------
  // Array Functions
  // ---------------------

  private static ImplicitCoercionStrategy ARRAY_ELEMENT_COERCION =
      new ImplicitCoercionStrategy() {
        @Override
        public Map<Integer, RelDataType> coerce(SqlCallBinding sqlCallBinding) {
          RelDataType arrayType = sqlCallBinding.getOperandType(0);
          if (arrayType.getSqlTypeName() != SqlTypeName.ARRAY) {
            return Collections.emptyMap();
          }

          RelDataType elementType = sqlCallBinding.getOperandType(1);

          List<RelDataType> elementTypes =
              ImmutableList.of(arrayType.getComponentType(), elementType);
          RelDataTypeFactory relDataTypeFactory = sqlCallBinding.getTypeFactory();
          RelDataType coercedElementType = relDataTypeFactory.leastRestrictive(elementTypes);
          if (coercedElementType == null) {
            return Collections.emptyMap();
          }

          RelDataType coercedArrayType = relDataTypeFactory.createArrayType(coercedElementType, -1);
          coercedArrayType =
              relDataTypeFactory.createTypeWithNullability(
                  coercedArrayType, arrayType.isNullable());

          Map<Integer, RelDataType> coercionMap = new HashMap<>();
          coercionMap.put(0, coercedArrayType);
          coercionMap.put(1, coercedElementType);

          return coercionMap;
        }
      };

  private static ImplicitCoercionStrategy ELEMENT_ARRAY_COERCION =
      new ImplicitCoercionStrategy() {
        @Override
        public Map<Integer, RelDataType> coerce(SqlCallBinding sqlCallBinding) {
          RelDataType elementType = sqlCallBinding.getOperandType(0);

          RelDataType arrayType = sqlCallBinding.getOperandType(1);
          if (arrayType.getSqlTypeName() != SqlTypeName.ARRAY) {
            return Collections.emptyMap();
          }

          List<RelDataType> elementTypes =
              ImmutableList.of(arrayType.getComponentType(), elementType);
          RelDataTypeFactory relDataTypeFactory = sqlCallBinding.getTypeFactory();
          RelDataType coercedElementType = relDataTypeFactory.leastRestrictive(elementTypes);
          if (coercedElementType == null) {
            return Collections.emptyMap();
          }

          RelDataType coercedArrayType = relDataTypeFactory.createArrayType(coercedElementType, -1);
          coercedArrayType =
              relDataTypeFactory.createTypeWithNullability(
                  coercedArrayType, arrayType.isNullable());

          Map<Integer, RelDataType> coercionMap = new HashMap<>();
          coercionMap.put(0, coercedElementType);
          coercionMap.put(1, coercedArrayType);

          return coercionMap;
        }
      };

  private static final ImplicitCoercionStrategy ARRAY_ARRAY_COERCION =
      new ImplicitCoercionStrategy() {
        @Override
        public Map<Integer, RelDataType> coerce(SqlCallBinding sqlCallBinding) {
          List<RelDataType> argumentTypes = sqlCallBinding.collectOperandTypes();
          if (argumentTypes.stream().anyMatch(arg -> arg.getSqlTypeName() != SqlTypeName.ARRAY)) {
            // If any are not arrays, then there isn't anything we can do.
            return Collections.emptyMap();
          }

          // Calcite has a bug where it doesn't properly take the least restrictive of ARRAY<STRING>
          // but it does do STRING properly
          // Basically the largest precision isn't taken.
          List<RelDataType> componentTypes =
              argumentTypes.stream()
                  .map(RelDataType::getComponentType)
                  .collect(Collectors.toList());
          RelDataTypeFactory typeFactory = sqlCallBinding.getTypeFactory();
          RelDataType leastRestrictiveComponentType = typeFactory.leastRestrictive(componentTypes);
          if (leastRestrictiveComponentType == null) {
            return Collections.emptyMap();
          }

          RelDataType leastRestrictiveArrayType =
              typeFactory.createArrayType(leastRestrictiveComponentType, -1);
          if (leastRestrictiveArrayType == null) {
            return Collections.emptyMap();
          }

          Map<Integer, RelDataType> coercionMap = new HashMap<>();
          for (int i = 0; i < argumentTypes.size(); i++) {
            if (argumentTypes.get(i) != leastRestrictiveArrayType) {
              coercionMap.put(i, leastRestrictiveArrayType);
            }
          }

          return coercionMap;
        }
      };

  private static final SqlOperandTypeChecker ARRAY_ELEMENT_OPERAND_CHECKER =
      arrayPrependAppendOperandChecker(true);
  private static final SqlOperandTypeChecker ELEMENT_ARRAY_OPERAND_CHECKER =
      arrayPrependAppendOperandChecker(false);

  private static SqlOperandTypeChecker arrayPrependAppendOperandChecker(boolean append) {
    return new SqlOperandTypeChecker() {
      @Override
      public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
        int elementIndex = 0;
        int arrayIndex = 1;
        if (append) {
          int temp = elementIndex;
          elementIndex = arrayIndex;
          arrayIndex = temp;
        }

        RelDataType elementType = callBinding.getOperandType(elementIndex);
        RelDataType arrayType = callBinding.getOperandType(arrayIndex);

        if (arrayType.getSqlTypeName() != SqlTypeName.ARRAY) {
          if (throwOnFailure) {
            throw UserException.validationError()
                .message(
                    "'"
                        + callBinding.getOperator().getName()
                        + "' expects an ARRAY, but instead got: "
                        + arrayType)
                .buildSilently();
          }

          return false;
        }

        boolean elementTypeMatchesArrayType =
            RelDataTypeEqualityComparer.areEquals(
                arrayType.getComponentType(),
                elementType,
                RelDataTypeEqualityComparer.Options.builder()
                    .withConsiderNullability(false)
                    .build());

        if (!elementTypeMatchesArrayType) {
          if (throwOnFailure) {
            throw UserException.validationError()
                .message(
                    "'"
                        + callBinding.getOperator().getName()
                        + "' expects 'T' to equal 'E' for "
                        + callBinding.getOperator().getName()
                        + "(T item, ARRAY<E> arr).")
                .buildSilently();
          }

          return false;
        }

        return true;
      }

      @Override
      public SqlOperandCountRange getOperandCountRange() {
        return SqlOperandCountRanges.of(2);
      }

      @Override
      public String getAllowedSignatures(SqlOperator op, String opName) {
        return null;
      }

      @Override
      public Consistency getConsistency() {
        return null;
      }

      @Override
      public boolean isOptional(int i) {
        return false;
      }
    };
  }

  private static final SqlReturnTypeInference ARRAY_ELEMENT_RETURN_TYPE_INFERENCE =
      arrayPrependAppendReturnTypeInference(true);
  private static final SqlReturnTypeInference ELEMENT_ARRAY_RETURN_TYPE_INFERENCE =
      arrayPrependAppendReturnTypeInference(false);

  private static SqlReturnTypeInference arrayPrependAppendReturnTypeInference(boolean append) {
    return new SqlReturnTypeInference() {
      @Override
      public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
        // Resulting concat needs to agree with the element and array type:
        int arrayIndex = append ? 0 : 1;
        return opBinding.getOperandType(arrayIndex);
      }
    };
  }

  /**
   * The "ARRAY_AGG(value) WITHIN GROUP(...) OVER (...)" aggregate function with gathers values into
   * an array.
   */
  public static final SqlAggFunction ARRAY_AGG =
      SqlBasicAggFunction.create(
              "ARRAY_AGG",
              SqlKind.LISTAGG,
              opBinding -> {
                if (opBinding.getOperandCount() != 1) {
                  throw new UnsupportedOperationException("Expected to have exactly 1 arg type.");
                }

                try {
                  RelDataType arrayItemType = opBinding.collectOperandTypes().get(0);
                  RelDataType arrayType =
                      opBinding.getTypeFactory().createArrayType(arrayItemType, -1);
                  RelDataType nonNullable =
                      opBinding.getTypeFactory().createTypeWithNullability(arrayType, false);
                  return nonNullable;
                } catch (Exception ex) {
                  throw ex;
                }
              },
              OperandTypes.ANY)
          .withFunctionType(SqlFunctionCategory.SYSTEM)
          .withGroupOrder(Optionality.OPTIONAL)
          .withAllowsNullTreatment(true);

  public static final SqlOperator EMPTY_ARRAY =
      SqlOperatorBuilder.name("EMPTY_ARRAY")
          .returnType(cascade(ReturnTypes.ARG0, SqlTypeTransforms.TO_NOT_NULLABLE))
          .operandTypes(OperandTypes.ARRAY)
          .build();

  private static final SqlOperandTypeChecker NUMERIC_ARRAY_TYPE_CHECKER =
      new SqlOperandTypeChecker() {
        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
          RelDataType operandType = callBinding.getOperandType(0);
          if (operandType.getSqlTypeName() != SqlTypeName.ARRAY) {
            if (throwOnFailure) {
              throw UserException.validationError()
                  .message("Expected argument to be an ARRAY.")
                  .build();
            }

            return false;
          }

          if (!SqlTypeName.NUMERIC_TYPES.contains(
              operandType.getComponentType().getSqlTypeName())) {
            if (throwOnFailure) {
              throw UserException.validationError()
                  .message("Expected argument to be an ARRAY of NUMERIC types.")
                  .build();
            }

            return false;
          }

          return true;
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
          return SqlOperandCountRanges.of(1);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
          return null;
        }

        @Override
        public Consistency getConsistency() {
          return null;
        }

        @Override
        public boolean isOptional(int i) {
          return false;
        }
      };

  public static final SqlOperator ARRAY_MIN =
      SqlOperatorBuilder.name("ARRAY_MIN")
          .returnType(DremioReturnTypes.ARG0_ARRAY_ELEMENT)
          .operandTypes(NUMERIC_ARRAY_TYPE_CHECKER)
          .build();

  public static final SqlOperator ARRAY_MAX =
      SqlOperatorBuilder.name("ARRAY_MAX")
          .returnType(DremioReturnTypes.ARG0_ARRAY_ELEMENT)
          .operandTypes(NUMERIC_ARRAY_TYPE_CHECKER)
          .build();

  public static final SqlOperator ARRAY_SUM =
      SqlOperatorBuilder.name("ARRAY_SUM")
          .returnType(DremioReturnTypes.ARG0_ARRAY_ELEMENT)
          .operandTypes(NUMERIC_ARRAY_TYPE_CHECKER)
          .build();

  public static final SqlOperator ARRAY_AVG =
      SqlOperatorBuilder.name("ARRAY_AVG")
          .returnType(DremioReturnTypes.DECIMAL_QUOTIENT_DEFAULT_PRECISION_SCALE)
          .operandTypes(NUMERIC_ARRAY_TYPE_CHECKER)
          .build();

  public static final SqlOperator ARRAY_CONTAINS =
      SqlOperatorBuilder.name("ARRAY_CONTAINS")
          .returnType(ReturnTypes.BOOLEAN_NULLABLE)
          .operandTypes(ARRAY_ELEMENT_OPERAND_CHECKER)
          .withImplicitCoercionStrategy(ARRAY_ELEMENT_COERCION)
          .build();

  public static final SqlOperator ARRAY_REMOVE =
      SqlOperatorBuilder.name("ARRAY_REMOVE")
          .returnType(ReturnTypes.ARG0)
          .operandTypes(ARRAY_ELEMENT_OPERAND_CHECKER)
          .withImplicitCoercionStrategy(ARRAY_ELEMENT_COERCION)
          .build();

  private static SqlReturnTypeInference LEAST_RESTRICTIVE_ARRAY =
      new SqlReturnTypeInference() {
        @Override
        public RelDataType inferReturnType(SqlOperatorBinding sqlOperatorBinding) {
          List<RelDataType> operandTypes = sqlOperatorBinding.collectOperandTypes();
          // For some reason least restrictive data type doesn't look into the array, so have to
          // manully do that:
          List<RelDataType> componentTypes =
              operandTypes.stream().map(x -> x.getComponentType()).collect(Collectors.toList());
          RelDataType leastRestrictiveComponentType =
              sqlOperatorBinding.getTypeFactory().leastRestrictive(componentTypes);
          RelDataType leastRestricitveArrayType =
              sqlOperatorBinding.getTypeFactory().leastRestrictive(operandTypes);
          RelDataType arrayType =
              sqlOperatorBinding
                  .getTypeFactory()
                  .createArrayType(leastRestrictiveComponentType, -1);
          return sqlOperatorBinding
              .getTypeFactory()
              .createTypeWithNullability(arrayType, leastRestricitveArrayType.isNullable());
        }
      };

  private static SqlOperandTypeChecker arraysOfSameType(
      String functionName, boolean allowVariadic) {
    return new SqlOperandTypeChecker() {
      @Override
      public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
        List<RelDataType> operandTypes = callBinding.collectOperandTypes();
        for (RelDataType operandType : operandTypes) {
          if (operandType.getSqlTypeName() != SqlTypeName.ARRAY) {
            if (throwOnFailure) {
              throw UserException.validationError()
                  .message("'" + functionName + "' expects all operands to be ARRAYs.")
                  .buildSilently();
            }

            return false;
          }
        }

        RelDataType first = operandTypes.get(0);
        for (int i = 1; i < operandTypes.size(); i++) {
          if (!RelDataTypeEqualityComparer.areEquals(
              first,
              operandTypes.get(i),
              RelDataTypeEqualityComparer.Options.builder()
                  .withConsiderNullability(false)
                  .withConsiderPrecision(false)
                  .withConsiderScale(false)
                  .build())) {
            if (throwOnFailure) {
              throw UserException.validationError()
                  .message(
                      "'" + functionName + "' expects all ARRAYs to be the same coercible type.")
                  .buildSilently();
            }

            return false;
          }
        }

        return true;
      }

      @Override
      public SqlOperandCountRange getOperandCountRange() {
        return allowVariadic ? SqlOperandCountRanges.from(2) : SqlOperandCountRanges.of(2);
      }

      @Override
      public String getAllowedSignatures(SqlOperator op, String opName) {
        return null;
      }

      @Override
      public Consistency getConsistency() {
        return null;
      }

      @Override
      public boolean isOptional(int i) {
        return false;
      }
    };
  }

  private static SqlOperandTypeChecker mapKeyValueTypeChecker(String functionName) {
    return new SqlOperandTypeChecker() {
      private final List<SqlTypeFamily> SUPPORTED_FAMILIES =
          Arrays.asList(SqlTypeFamily.BOOLEAN, SqlTypeFamily.NUMERIC, SqlTypeFamily.STRING);

      @Override
      public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
        List<RelDataType> relDataTypes = callBinding.collectOperandTypes();
        RelDataType valueType = null;
        List<RelDataType> valuesTypes = new LinkedList<>();

        for (int i = 0; i < relDataTypes.size(); i++) {
          if (!callBinding.operand(i).isA(EnumSet.of(SqlKind.LITERAL, SqlKind.CAST))) {
            if (throwOnFailure) {
              throw UserException.validationError()
                  .message("'" + functionName + "' expects all arguments to be literals.")
                  .build();
            }
            return false;
          }
        }

        for (int i = 0; i < relDataTypes.size(); i += 2) {
          RelDataType relDataType = relDataTypes.get(i);
          if (!SqlTypeFamily.STRING.contains(relDataType)) {
            if (throwOnFailure) {
              throw UserException.validationError()
                  .message("'" + functionName + "' expects keys to be Characters.")
                  .build();
            }
            return false;
          }
        }

        for (int i = 1; i < relDataTypes.size(); i += 2) {
          RelDataType relDataType = relDataTypes.get(i);
          if (!SUPPORTED_FAMILIES.stream().anyMatch(type -> type.contains(relDataType))) {
            if (throwOnFailure) {
              throw UserException.validationError()
                  .message(
                      "'"
                          + functionName
                          + "' expects values to be Numbers, Booleans or Characters.")
                  .build();
            }

            return false;
          }
          valuesTypes.add(relDataType);
        }

        valueType = callBinding.getTypeFactory().leastRestrictive(valuesTypes);
        if (valueType == null) {
          if (throwOnFailure) {
            throw UserException.validationError()
                .message("'" + functionName + "' expects all values to have coercible types.")
                .build();
          }
          return false;
        }
        return true;
      }

      @Override
      public SqlOperandCountRange getOperandCountRange() {
        return new SqlOperandCountRange() {
          @Override
          public boolean isValidCount(int count) {
            return count >= 2 && count % 2 == 0;
          }

          @Override
          public int getMin() {
            return 2;
          }

          @Override
          public int getMax() {
            return -1;
          }
        };
      }

      @Override
      public String getAllowedSignatures(SqlOperator op, String opName) {
        return null;
      }

      @Override
      public Consistency getConsistency() {
        return null;
      }

      @Override
      public boolean isOptional(int i) {
        return false;
      }
    };
  }

  public static final SqlOperator ARRAY_CONCAT =
      SqlOperatorBuilder.name("ARRAY_CONCAT")
          .returnType(LEAST_RESTRICTIVE_ARRAY)
          .operandTypes(arraysOfSameType("ARRAY_CONCAT", true))
          .withImplicitCoercionStrategy(ARRAY_ARRAY_COERCION)
          .build();

  public static final SqlOperator ARRAY_CAT = SqlOperatorBuilder.alias("ARRAY_CAT", ARRAY_CONCAT);

  public static final SqlOperator ARRAY_DISTINCT =
      SqlOperatorBuilder.name("ARRAY_DISTINCT")
          .returnType(ReturnTypes.ARG0)
          .operandTypes(SqlOperands.ARRAY)
          .build();

  public static final SqlOperator ARRAY_PREPEND =
      SqlOperatorBuilder.name("ARRAY_PREPEND")
          .returnType(ELEMENT_ARRAY_RETURN_TYPE_INFERENCE)
          .operandTypes(ELEMENT_ARRAY_OPERAND_CHECKER)
          .withImplicitCoercionStrategy(ELEMENT_ARRAY_COERCION)
          .build();

  public static final SqlOperator ARRAY_APPEND =
      SqlOperatorBuilder.name("ARRAY_APPEND")
          .returnType(ARRAY_ELEMENT_RETURN_TYPE_INFERENCE)
          .operandTypes(ARRAY_ELEMENT_OPERAND_CHECKER)
          .withImplicitCoercionStrategy(ARRAY_ELEMENT_COERCION)
          .build();

  public static final SqlOperator ARRAY_REMOVE_AT =
      SqlOperatorBuilder.name("ARRAY_REMOVE_AT")
          .returnType(ReturnTypes.ARG0)
          .operandTypes(SqlOperands.ARRAY, SqlOperands.INTEGER)
          .build();

  public static final SqlOperator ARRAY_SIZE =
      SqlOperatorBuilder.name("ARRAY_SIZE")
          .returnType(ReturnTypes.INTEGER_NULLABLE)
          .operandTypes(SqlOperands.ARRAY)
          .build();

  public static final SqlOperator ARRAY_LENGTH =
      SqlOperatorBuilder.alias("ARRAY_LENGTH", ARRAY_SIZE);

  public static final SqlOperator ARRAY_POSITION =
      SqlOperatorBuilder.name("ARRAY_POSITION")
          .returnType(ReturnTypes.INTEGER_NULLABLE)
          .operandTypes(ELEMENT_ARRAY_OPERAND_CHECKER)
          .withImplicitCoercionStrategy(ELEMENT_ARRAY_COERCION)
          .build();

  public static final SqlOperator ARRAY_GENERATE_RANGE =
      SqlOperatorBuilder.name("ARRAY_GENERATE_RANGE")
          .returnType(
              opBinding -> {
                RelDataTypeFactory typeFactory = opBinding.getTypeFactory();

                RelDataType withoutNullability =
                    opBinding
                        .getTypeFactory()
                        .createTypeWithNullability(
                            typeFactory.createSqlType(SqlTypeName.INTEGER), false);
                RelDataType asArray =
                    opBinding.getTypeFactory().createArrayType(withoutNullability, -1);
                return asArray;
              })
          .operandTypes(SqlOperands.INTEGER, SqlOperands.INTEGER, SqlOperands.INTEGER_OPTIONAL)
          .build();

  public static final SqlOperator SUBLIST =
      SqlOperatorBuilder.name("SUBLIST")
          .returnType(ReturnTypes.ARG0)
          .operandTypes(SqlOperands.ARRAY, SqlOperands.INTEGER, SqlOperands.INTEGER)
          .build();

  public static final SqlOperator ARRAY_COMPACT =
      SqlOperatorBuilder.name("ARRAY_COMPACT")
          .returnType(
              opBinding -> {
                RelDataType arrayType = opBinding.getOperandType(0);

                RelDataType baseType =
                    opBinding
                        .getTypeFactory()
                        .createSqlType(arrayType.getComponentType().getSqlTypeName());
                RelDataType withoutNullability =
                    opBinding.getTypeFactory().createTypeWithNullability(baseType, false);
                RelDataType asArray =
                    opBinding.getTypeFactory().createArrayType(withoutNullability, -1);

                RelDataType asArrayWithNullability =
                    opBinding
                        .getTypeFactory()
                        .createTypeWithNullability(asArray, arrayType.isNullable());

                return asArrayWithNullability;
              })
          .operandTypes(SqlOperands.ARRAY)
          .build();

  public static final SqlOperator ARRAY_SLICE =
      SqlOperatorBuilder.name("ARRAY_SLICE")
          .returnType(ReturnTypes.ARG0)
          .operandTypes(SqlOperands.ARRAY, SqlOperands.INTEGER, SqlOperands.INTEGER_OPTIONAL)
          .build();

  public static final SqlOperator ARRAY_INSERT =
      SqlOperatorBuilder.name("ARRAY_INSERT")
          .returnType(ReturnTypes.ARG0)
          .operandTypes(
              new SqlOperandTypeChecker() {
                @Override
                public boolean checkOperandTypes(
                    SqlCallBinding callBinding, boolean throwOnFailure) {
                  RelDataType arrayType = callBinding.getOperandType(0);
                  RelDataType indexType = callBinding.getOperandType(1);
                  RelDataType elementType = callBinding.getOperandType(2);

                  if (arrayType.getSqlTypeName() != SqlTypeName.ARRAY) {
                    if (throwOnFailure) {
                      throw UserException.validationError()
                          .message("'ARRAY_INSERT' expects an ARRAY, but instead got: " + arrayType)
                          .build();
                    }
                    return false;
                  }
                  if (elementType.getSqlTypeName() != arrayType.getComponentType().getSqlTypeName()
                      && elementType.getSqlTypeName() != SqlTypeName.NULL) {
                    if (throwOnFailure) {
                      throw UserException.validationError()
                          .message(
                              "'ARRAY_INSERT' expects 'T' to equal 'E' for ARRAY_INSERT(ARRAY<E> arr, Integer index, T item).")
                          .build();
                    }

                    return false;
                  }
                  if (indexType.getSqlTypeName() != SqlTypeName.INTEGER) {
                    if (throwOnFailure) {
                      throw UserException.validationError()
                          .message(
                              "'ARRAY_INSERT' expects an INTEGER, but instead got: " + indexType)
                          .build();
                    }
                    return false;
                  }
                  return true;
                }

                @Override
                public SqlOperandCountRange getOperandCountRange() {
                  return SqlOperandCountRanges.of(3);
                }

                @Override
                public String getAllowedSignatures(SqlOperator op, String opName) {
                  return null;
                }

                @Override
                public Consistency getConsistency() {
                  return null;
                }

                @Override
                public boolean isOptional(int i) {
                  return false;
                }
              })
          .build();

  public static final SqlOperator ARRAYS_OVERLAP =
      SqlOperatorBuilder.name("ARRAYS_OVERLAP")
          .returnType(ReturnTypes.BOOLEAN_NULLABLE)
          .operandTypes(arraysOfSameType("ARRAYS_OVERLAP", false))
          .withImplicitCoercionStrategy(ARRAY_ARRAY_COERCION)
          .build();

  public static final SqlOperator SET_UNION =
      SqlOperatorBuilder.name("SET_UNION")
          .returnType(LEAST_RESTRICTIVE_ARRAY)
          .operandTypes(arraysOfSameType("SET_UNION", false))
          .withImplicitCoercionStrategy(ARRAY_ARRAY_COERCION)
          .build();

  public static final SqlOperator ARRAY_INTERSECTION =
      SqlOperatorBuilder.name("ARRAY_INTERSECTION")
          .returnType(LEAST_RESTRICTIVE_ARRAY)
          .operandTypes(arraysOfSameType("ARRAY_INTERSECTION", false))
          .withImplicitCoercionStrategy(ARRAY_ARRAY_COERCION)
          .build();

  public static final SqlOperator ARRAY_TO_STRING =
      SqlOperatorBuilder.name("ARRAY_TO_STRING")
          .returnType(SqlTypeName.VARCHAR)
          .operandTypes(SqlOperands.ARRAY, SqlOperands.VARCHAR)
          .build();

  public static final SqlOperator LIST_TO_DELIMITED_STRING =
      SqlOperatorBuilder.name("LIST_TO_DELIMITED_STRING")
          .returnType(SqlTypeName.VARCHAR)
          .operandTypes(SqlOperands.ANY, SqlOperands.VARCHAR)
          .build();

  public static final SqlOperator ARRAY_FREQUENCY =
      SqlOperatorBuilder.name("ARRAY_FREQUENCY")
          .returnType(
              opBinding -> {
                RelDataType arrayType = opBinding.getOperandType(0);
                RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
                RelDataType mapType =
                    typeFactory.createMapType(
                        arrayType.getComponentType(),
                        JavaTypeFactoryImpl.INSTANCE.createSqlType(SqlTypeName.INTEGER));
                return typeFactory.createTypeWithNullability(mapType, arrayType.isNullable());
              })
          .operandTypes(
              new SqlOperandTypeChecker() {
                @Override
                public boolean checkOperandTypes(
                    SqlCallBinding callBinding, boolean throwOnFailure) {
                  RelDataType operandType = callBinding.getOperandType(0);
                  if (operandType.getSqlTypeName() != SqlTypeName.ARRAY) {
                    if (throwOnFailure) {
                      throw UserException.validationError()
                          .message("'ARRAY_FREQUENCY' expects an ARRAY argument.")
                          .buildSilently();
                    }

                    return false;
                  }
                  RelDataType componentType = operandType.getComponentType();

                  if (!SqlTypeFamily.DATE.contains(componentType)
                      && !SqlTypeFamily.TIME.contains(componentType)
                      && !SqlTypeFamily.TIMESTAMP.contains(componentType)
                      && !SqlTypeFamily.STRING.contains(componentType)
                      && !SqlTypeFamily.NUMERIC.contains(componentType)
                      && !SqlTypeFamily.BOOLEAN.contains(componentType)) {
                    if (throwOnFailure) {
                      throw UserException.validationError()
                          .message("'ARRAY_FREQUENCY' expects an ARRAY of primitive types.")
                          .buildSilently();
                    }

                    return false;
                  }

                  return true;
                }

                @Override
                public SqlOperandCountRange getOperandCountRange() {
                  return SqlOperandCountRanges.of(1);
                }

                @Override
                public String getAllowedSignatures(SqlOperator op, String opName) {
                  return null;
                }

                @Override
                public Consistency getConsistency() {
                  return null;
                }

                @Override
                public boolean isOptional(int i) {
                  return false;
                }
              })
          .build();

  // ---------------------
  // MAP Functions
  // ---------------------

  public static final SqlOperator LAST_MATCHING_MAP_ENTRY_FOR_KEY =
      SqlOperatorBuilder.name(MapFunctions.LAST_MATCHING_ENTRY_FUNC)
          .returnType(
              new SqlReturnTypeInference() {
                @Override
                public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
                  RelDataType keyType = opBinding.getOperandType(0).getKeyType();
                  RelDataType valueType = opBinding.getOperandType(0).getValueType();

                  // This is just to comply with the item operator
                  // But we should fix last_matching_map_entry to differentiate between key not
                  // found and key found but value is null.
                  RelDataType withNullable =
                      opBinding.getTypeFactory().createTypeWithNullability(valueType, true);
                  return opBinding
                      .getTypeFactory()
                      .createStructType(
                          ImmutableList.of(keyType, withNullable),
                          ImmutableList.of("key", "value"));
                }
              })
          .operandTypes(
              new SqlOperandTypeChecker() {
                @Override
                public boolean checkOperandTypes(
                    SqlCallBinding callBinding, boolean throwOnFailure) {
                  SqlTypeName collectionType = callBinding.getOperandType(0).getSqlTypeName();
                  if (collectionType != SqlTypeName.MAP) {
                    if (!throwOnFailure) {
                      return false;
                    }

                    throw UserException.validationError()
                        .message(
                            "Expected first argument to 'last_matching_entry_func' to be a map, but instead got: "
                                + collectionType)
                        .buildSilently();
                  }

                  SqlTypeName indexType = callBinding.getOperandType(1).getSqlTypeName();
                  SqlTypeName keyType = callBinding.getOperandType(0).getKeyType().getSqlTypeName();
                  if (indexType != keyType) {
                    if (!throwOnFailure) {
                      return false;
                    }

                    throw UserException.validationError()
                        .message(
                            "Expected second argument to 'last_matching_entry_func' to match the key type of the map. "
                                + "Map key type is: "
                                + keyType
                                + "and "
                                + "index type is: "
                                + indexType)
                        .buildSilently();
                  }

                  return true;
                }

                @Override
                public SqlOperandCountRange getOperandCountRange() {
                  return SqlOperandCountRanges.of(2);
                }

                @Override
                public String getAllowedSignatures(SqlOperator op, String opName) {
                  return null;
                }

                @Override
                public Consistency getConsistency() {
                  return null;
                }

                @Override
                public boolean isOptional(int i) {
                  return false;
                }
              })
          .build();

  // We defer the return type to execution (since we don't know what the return type is without
  // inspecting the parameters).
  public static final SqlOperator KVGEN =
      SqlOperatorBuilder.name("KVGEN")
          .returnType(SqlTypeName.ANY)
          .operandTypes(SqlOperands.ANY)
          .build();
  public static final SqlOperator MAPPIFY = SqlOperatorBuilder.alias("MAPPIFY", KVGEN);

  public static final SqlOperator MAP_KEYS =
      SqlOperatorBuilder.name("MAP_KEYS")
          .returnType(
              JavaTypeFactoryImpl.INSTANCE.createArrayType(
                  JavaTypeFactoryImpl.INSTANCE.createSqlType(SqlTypeName.VARCHAR), -1))
          .operandTypes(SqlOperands.MAP)
          .build();

  public static final SqlOperator MAP_VALUES =
      SqlOperatorBuilder.name("MAP_VALUES")
          .returnType(
              JavaTypeFactoryImpl.INSTANCE.createArrayType(
                  JavaTypeFactoryImpl.INSTANCE.createSqlType(SqlTypeName.ANY), -1))
          .operandTypes(SqlOperands.MAP)
          .build();

  public static final SqlOperator MAP_CONSTRUCT =
      SqlOperatorBuilder.name("MAP_CONSTRUCT")
          .returnType(DremioReturnTypes.TO_MAP)
          .operandTypes(mapKeyValueTypeChecker("MAP_CONSTRUCT"))
          .build();

  private static final SqlOperator MAP = SqlOperatorBuilder.alias("MAP", MAP_CONSTRUCT);

  public static final SqlOperator DREMIO_INTERNAL_BUILDMAP =
      SqlOperatorBuilder.name("DREMIO_INTERNAL_BUILDMAP")
          .returnType(DremioReturnTypes.ARRAYS_TO_MAP)
          .operandTypes(
              OperandTypes.sequence("<array>, <array>", OperandTypes.ARRAY, OperandTypes.ARRAY))
          .build();

  // ---------------------
  // TDigest Functions
  // ---------------------

  public static final SqlAggFunction APPROX_PERCENTILE =
      new TDigest.SqlApproximatePercentileFunction();
  public static final SqlFunction TDIGEST_QUANTILE = new TDigest.SqlTDigestQuantileFunction();

  // ---------------------
  // OVERRIDE Behavior
  // ---------------------
  public static final SqlOperator DATE_TRUNC =
      SqlOperatorBuilder.name("DATE_TRUNC")
          .returnType(ReturnTypes.ARG1)
          .operandTypes(
              SqlOperands.CHAR_TYPES,
              UnionedSqlOperand.create(SqlOperands.INTERVAL_TYPES, SqlOperands.DATETIME_TYPES))
          .withImplicitCoercionStrategy(
              (sqlCallBinding) -> {
                if (!SqlTypeName.CHAR_TYPES.contains(
                    sqlCallBinding.getOperandType(1).getSqlTypeName())) {
                  return Collections.emptyMap();
                }

                Map<Integer, RelDataType> coercions = new HashMap<>();
                coercions.put(1, sqlCallBinding.getTypeFactory().createSqlType(SqlTypeName.DATE));
                return coercions;
              })
          .build();

  public static final SqlOperator NEXT_DAY =
      SqlOperatorBuilder.name("NEXT_DAY")
          .returnType(ReturnTypes.DATE)
          .operandTypes(
              UnionedSqlOperand.create(SqlOperands.DATE, SqlOperands.TIMESTAMP),
              SqlOperands.CHAR_TYPES)
          .build();

  // ---------------------
  // Non Deterministic Functions
  // ---------------------

  public static final SqlOperator RAND =
      SqlOperatorBuilder.name("RAND")
          .returnType(SqlStdOperatorTable.RAND.getReturnTypeInference())
          .operandTypes(SqlStdOperatorTable.RAND.getOperandTypeChecker())
          .withDeterminism(false)
          .build();

  // ---------------------
  // Dynamic Function
  // ---------------------
  public static final SqlOperator NOW =
      SqlOperatorBuilder.alias("NOW", SqlStdOperatorTable.CURRENT_TIMESTAMP);
  public static final SqlOperator STATEMENT_TIMESTAMP =
      SqlOperatorBuilder.alias("STATEMENT_TIMESTAMP", SqlStdOperatorTable.CURRENT_TIMESTAMP);
  public static final SqlOperator TRANSACTION_TIMESTAMP =
      SqlOperatorBuilder.alias("TRANSACTION_TIMESTAMP", SqlStdOperatorTable.CURRENT_TIMESTAMP);
  public static final SqlOperator CURRENT_TIMESTAMP_UTC =
      SqlOperatorBuilder.alias("CURRENT_TIMESTAMP_UTC", SqlStdOperatorTable.CURRENT_TIMESTAMP);
  public static final SqlOperator TIMEOFDAY =
      SqlOperatorBuilder.name("TIMEOFDAY")
          .returnType(SqlTypeName.VARCHAR)
          .noOperands()
          .withDynanism(true)
          .build();

  public static final SqlOperator IS_MEMBER =
      SqlOperatorBuilder.name("IS_MEMBER")
          .returnType(SqlTypeName.BOOLEAN)
          .operandTypes(SqlOperands.STRING_TYPES, SqlOperands.BOOLEAN_OPTIONAL)
          .withDynanism(true)
          .build();

  public static final SqlOperator USER =
      SqlOperatorBuilder.name("USER")
          .returnType(SqlTypeName.VARCHAR)
          .noOperands()
          .withDynanism(true)
          .build();

  public static final SqlOperator SESSION_USER = SqlOperatorBuilder.alias("SESSION_USER", USER);

  public static final SqlOperator SYSTEM_USER = SqlOperatorBuilder.alias("SYSTEM_USER", USER);

  public static final SqlOperator QUERY_USER = SqlOperatorBuilder.alias("QUERY_USER", USER);

  public static final SqlOperator LAST_QUERY_ID =
      new SqlBaseContextVariable(
          "LAST_QUERY_ID",
          cascade(ReturnTypes.VARCHAR_2000, SqlTypeTransforms.FORCE_NULLABLE),
          SqlFunctionCategory.SYSTEM) {};

  public static final SqlOperator CURRENT_DATE_UTC =
      SqlOperatorBuilder.alias("CURRENT_DATE_UTC", SqlStdOperatorTable.CURRENT_DATE);

  public static final SqlOperator CURRENT_TIME_UTC =
      SqlOperatorBuilder.alias("CURRENT_TIME_UTC", SqlStdOperatorTable.CURRENT_TIME);

  public static final SqlOperator UNIX_TIMESTAMP =
      SqlOperatorBuilder.name("UNIX_TIMESTAMP")
          .returnType(SqlTypeName.BIGINT)
          .noOperands()
          .withDynanism(true)
          .build();

  private DremioSqlOperatorTable() {}

  /** Returns the standard operator table, creating it if necessary. */
  public static synchronized DremioSqlOperatorTable instance() {
    if (instance == null) {
      // Creates and initializes the standard operator table.
      // Uses two-phase construction, because we can't initialize the
      // table until the constructor of the sub-class has completed.
      instance = new DremioSqlOperatorTable();
      instance.init();
    }
    return instance;
  }
}
