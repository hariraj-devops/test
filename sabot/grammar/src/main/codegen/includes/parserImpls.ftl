<#--

    Copyright (C) 2017-2019 Dremio Corporation

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

-->

<#--
  Add implementations of additional parser statements here.
  Each implementation should return an object of SqlNode type.

  Example of SqlShowTables() implementation:
  SqlNode SqlShowTables()
  {
    ...local variables...
  }
  {
    <SHOW> <TABLES>
    ...
    {
      return SqlShowTables(...)
    }
  }
-->
/**
 *   SHOW TABLES
 *   [ AT ( REF[ERENCE] | BRANCH | TAG | COMMIT ) refValue [AS OF timestamp] ]
 *   [ ( FROM | IN ) source ]
 *   [ LIKE 'pattern' ]
 */
SqlNode SqlShowTables() :
{
    SqlParserPos pos;
    ReferenceType refType = null;
    SqlIdentifier refValue = null;
    SqlNode timestamp = null;
    SqlIdentifier source = null;
    SqlNode likePattern = null;
}
{
    <SHOW> { pos = getPos(); }
    <TABLES>
    [
        <AT>
        (
            <REF> { refType = ReferenceType.REFERENCE; }
            |
            <REFERENCE> { refType = ReferenceType.REFERENCE; }
            |
            <BRANCH> { refType = ReferenceType.BRANCH; }
            |
            <TAG> { refType = ReferenceType.TAG; }
            |
            <COMMIT> { refType = ReferenceType.COMMIT; }
        )
        { refValue = SimpleIdentifier(); }
        [ <AS> <OF> timestamp = StringLiteral() ]
    ]
    [
        (<FROM> | <IN>) { source = CompoundIdentifier(); }
    ]
    [
        <LIKE> { likePattern = StringLiteral(); }
    ]
    {
        return new SqlShowTables(pos, refType, refValue, timestamp, source, likePattern);
    }
}

/**
 *   SHOW VIEWS
 *   [ AT ( REF[ERENCE] | BRANCH | TAG | COMMIT ) refValue [AS OF timestamp] ]
 *   [ ( FROM | IN ) source ]
 *   [ LIKE 'pattern' ]
 */
SqlNode SqlShowViews() :
{
    SqlParserPos pos;
    ReferenceType refType = null;
    SqlIdentifier refValue = null;
    SqlNode timestamp = null;
    SqlIdentifier source = null;
    SqlNode likePattern = null;
}
{
    <SHOW> { pos = getPos(); }
    <VIEWS>
    [
        <AT>
        (
            <REF> { refType = ReferenceType.REFERENCE; }
            |
            <REFERENCE> { refType = ReferenceType.REFERENCE; }
            |
            <BRANCH> { refType = ReferenceType.BRANCH; }
            |
            <TAG> { refType = ReferenceType.TAG; }
            |
            <COMMIT> { refType = ReferenceType.COMMIT; }
        )
        { refValue = SimpleIdentifier(); }
        [ <AS> <OF> timestamp = StringLiteral() ]
    ]
    [
        (<FROM> | <IN>) { source = CompoundIdentifier(); }
    ]
    [
        <LIKE> { likePattern = StringLiteral(); }
    ]
    {
        return new SqlShowViews(pos, refType, refValue, timestamp, source, likePattern);
    }
}

/**
 * Parses statement
 * SHOW FILES [{FROM | IN} schema]
 */
SqlNode SqlShowFiles() :
{
    SqlParserPos pos = null;
    SqlIdentifier db = null;
}
{
    <SHOW> { pos = getPos(); }
    <FILES>
    [
        (<FROM> | <IN>) { db = CompoundIdentifier(); }
    ]
    {
        return new SqlShowFiles(pos, db);
    }
}

/**
 * Parses a show tblproperties statement
 * SHOW TBLPROPERTIES tblName
 */
SqlNode SqlShowTableProperties() :
{
    SqlParserPos pos;
    SqlIdentifier tblName = null;
}
{
    <SHOW> { pos = getPos(); }
    <TBLPROPERTIES>
    tblName = CompoundIdentifier()
    {
        return new SqlShowTableProperties(pos, tblName);
    }
}

/**
 * Parses statement SHOW {DATABASES | SCHEMAS} [LIKE 'pattern']
 */
SqlNode SqlShowSchemas() :
{
    SqlParserPos pos;
    SqlNode likePattern = null;
}
{
    <SHOW> { pos = getPos(); }
    (<DATABASES> | <SCHEMAS>)
    [
        <LIKE> { likePattern = StringLiteral(); }
    ]
    {
        return new SqlShowSchemas(pos, likePattern);
    }
}

/**
 * Parses statement
 * { DESCRIBE TABLE | DESCRIBE | DESC } tblname
 * [ AT (BRANCH | TAG | COMMIT | REF | REFERENCE) ]
 * [ col_name | wildcard ]
 */
SqlNode SqlDescribeTable() :
{
    SqlParserPos pos;
    SqlIdentifier table;
    SqlIdentifier column = null;
    SqlTableVersionSpec sqlTableVersionSpec = SqlTableVersionSpec.NOT_SPECIFIED;
}
{
    ( <DESCRIBE> <TABLE>| <DESCRIBE> | <DESC> ) { pos = getPos(); }
    table = CompoundIdentifier()
    [ sqlTableVersionSpec = ATVersionSpec() ]
    (
        column = CompoundIdentifier()
        |
        E()
    )
    {
        return new SqlDescribeDremioTable(pos, table, sqlTableVersionSpec, column);
    }
}

/**
 * Sets SQL context so that unqualified identifiers can be used in SQL
 */
SqlNode SqlUseSchema():
{
    SqlIdentifier schema = null;
    SqlParserPos pos = SqlParserPos.ZERO;
}
{
    <USE> [ { pos = getPos(); }
    schema = CompoundIdentifier() ]
    {
        return new SqlUseSchema(pos, schema);
    }
}

/** Parses an optional field list and makes sure no field is a "*". */
SqlNodeList ParseOptionalFieldList(String relType) :
{
    SqlNodeList fieldList;
}
{
    fieldList = ParseRequiredFieldList(relType)
    {
        return fieldList;
    }
    |
    {
        return SqlNodeList.EMPTY;
    }
}
SqlNodeList ParseOptionalFieldListWithMasking(String relType) :
{
    SqlNodeList fieldList;
}
{
    fieldList = ParseRequiredFieldListWithMasking(relType)
    {
        return fieldList;
    }
    |
    {
        return SqlNodeList.EMPTY;
    }
}

/** Parses a required field list and makes sure no field is a "*". */
SqlNodeList ParseRequiredFieldList(String relType) :
{
    SqlNodeList fieldList = new SqlNodeList(getPos());
}
{
    <LPAREN>
    SimpleIdentifierCommaList(fieldList.getList())
    <RPAREN>
    {
        for(SqlNode node : fieldList)
        {
            if (((SqlIdentifier)node).isStar())
                throw new ParseException(String.format("%s's field list has a '*', which is invalid.", relType));
        }
        return fieldList;
    }
}
SqlNodeList ParseRequiredFieldListWithMasking(String relType) :
{
    final Span s;
    final List<SqlNode> fieldList = new ArrayList<SqlNode>();
}
{
    <LPAREN> { s = span(); }
    ColumnNamesWithMasking(fieldList)
    (
        <COMMA> ColumnNamesWithMasking(fieldList)
    )*
    <RPAREN> {
        for(SqlNode node : fieldList)
        {
            if (((SqlColumnPolicyPair)node).getName().isStar())
                throw new ParseException(String.format("%s's field list has a '*', which is invalid.", relType));
        }
        return new SqlNodeList(fieldList, s.end(this));
    }
}

void ColumnNamesWithMasking(List<SqlNode> list) :
{
    final SqlIdentifier id;
    SqlPolicy policy = null;
    final Span s = span();
}
{
    id = SimpleIdentifier()
    (
      <MASKING> <POLICY>  policy = Policy()
      {
          list.add(new SqlColumnPolicyPair(s.add(id).end(this), id, policy));
      }
      |
      {
          list.add(new SqlColumnPolicyPair(s.add(id).end(this), id, null));
      }
    )
}

/**
 * Parses a create view or replace existing view statement.
 *   CREATE [OR REPLACE] VIEW view_name [ (field1, field2 ...) ] AS select_statement
 */
SqlNode SqlCreateOrReplace() :
{
    SqlParserPos pos;
    boolean replace = false;
    SqlIdentifier viewName;
    SqlNode query;
    SqlNodeList fieldList = SqlNodeList.EMPTY;
    SqlIdentifier name;
    SqlNode expression;
    SqlDataTypeSpec scalarReturnType = null;
    SqlNodeList tabularReturnType = SqlNodeList.EMPTY;
    boolean ifNotExists = false;
    SqlPolicy policy = null;
    boolean nullable = true;
    SqlComplexDataTypeSpec scalarReturnTypeSpec = null;
    SqlFunctionReturnType returnType;
    ReferenceType refType = null;
    SqlIdentifier refValue = null;
    SqlTableVersionSpec sqlTableVersionSpec = SqlTableVersionSpec.NOT_SPECIFIED;
}
{
    <CREATE> { pos = getPos(); }
    [ <OR> <REPLACE> { replace = true; } ]
    (
      <FUNCTION>
        [ <IF> <NOT> <EXISTS> { ifNotExists = true; } ]
        {
          if (replace && ifNotExists)
            throw new ParseException("'OR REPLACE' and 'IF NOT EXISTS' can not both be set.");
        }
        name = CompoundIdentifier()
        fieldList = ParseFunctionFieldList()
        [ sqlTableVersionSpec = WriteableAtVersionSpec() ]
      <RETURNS>
        (
          <TABLE> {
            tabularReturnType = ParseFunctionReturnFieldList();
          }
        |
          {
            scalarReturnType = DataType();
            nullable = NullableOptDefaultTrue();
            scalarReturnTypeSpec = new SqlComplexDataTypeSpec(scalarReturnType.withNullable(nullable));
          }
        )
      <RETURN> {
        expression = OrderedQueryOrExpr(ExprContext.ACCEPT_ALL);
        returnType = new SqlFunctionReturnType(pos, scalarReturnTypeSpec, tabularReturnType);
        return new SqlCreateFunction(
          pos,
          SqlLiteral.createBoolean(replace, SqlParserPos.ZERO),
          name,
          fieldList,
          expression,
          SqlLiteral.createBoolean(ifNotExists, SqlParserPos.ZERO),
          returnType,
          sqlTableVersionSpec);
      }
      |
      (<VIEW>|<VDS>)
      viewName = CompoundIdentifier()
      fieldList = ParseOptionalFieldListWithMasking("View")
      [
        <AT>
        (
          <REF> { refType = ReferenceType.REFERENCE; }
          |
          <REFERENCE> { refType = ReferenceType.REFERENCE; }
          |
          <BRANCH> { refType = ReferenceType.BRANCH; }
        )
        { refValue = SimpleIdentifier(); }
      ]
      [
          <ROW><ACCESS><POLICY>
          {
              policy = Policy();
          }
      ]
      <AS> { pos = pos.plus(getPos()); }
      query = OrderedQueryOrExpr(ExprContext.ACCEPT_QUERY)
      {
          return new SqlCreateView(pos, viewName, fieldList, query, replace, policy, refType, refValue);
      }
    )
}

/**
*  DROP UDF
*/
SqlNode SqlDropFunction() :
{
  SqlParserPos pos;
  SqlLiteral ifExists = SqlLiteral.createBoolean(false, SqlParserPos.ZERO);
  SqlIdentifier name;
  SqlTableVersionSpec sqlTableVersionSpec = SqlTableVersionSpec.NOT_SPECIFIED;
}
{
  <DROP> { pos = getPos(); }
  <FUNCTION>
  [ <IF> <EXISTS> { ifExists = SqlLiteral.createBoolean(true, SqlParserPos.ZERO); } ]
  name = CompoundIdentifier()
  [ sqlTableVersionSpec = WriteableAtVersionSpec() ]
  {
    return new SqlDropFunction(pos, ifExists, name, sqlTableVersionSpec);
  }
}

/**
*  DESCRIBE UDF
*/
SqlNode SqlDescribeFunction() :
{
  SqlParserPos pos;
  SqlIdentifier function;
  SqlTableVersionSpec sqlTableVersionSpec = SqlTableVersionSpec.NOT_SPECIFIED;
}
{
  (<DESCRIBE> | <DESC>) { pos = getPos(); }
  <FUNCTION>
  function = CompoundIdentifier()
  [ sqlTableVersionSpec = ATVersionSpec() ]
  {
    return new SqlDescribeFunction(pos, function, sqlTableVersionSpec);
  }
}

/**
 *   SHOW FUNCTIONS
 *   [ LIKE 'pattern' ]
 */
SqlNode SqlShowFunctions() :
{
    SqlParserPos pos;
    SqlNode likePattern = null;
}
{
    <SHOW> { pos = getPos(); }
    <FUNCTIONS>
    [
        <LIKE> { likePattern = StringLiteral(); }
    ]
    {
        return new SqlShowFunctions(pos, likePattern);
    }
}

SqlNodeList ParseFunctionReturnFieldList() :
{
  SqlNodeList fieldList = new SqlNodeList(getPos());
}
{
  <LPAREN>
    FunctionReturnTypeCommaList(fieldList.getList())
  <RPAREN>
  {
    return fieldList;
  }
}

void FunctionReturnTypeCommaList(List<SqlNode> list) :
{
  SqlReturnField returnField;
}
{
  (
    returnField = ReturnKeyValuePair() { list.add(returnField); }
    (
      <COMMA> returnField = ReturnKeyValuePair() {
        list.add(returnField);
      }
    )*
  )?
}

SqlReturnField ReturnKeyValuePair() :
{
  SqlNodeList pair = new SqlNodeList(getPos());
  SqlIdentifier name;
  SqlDataTypeSpec type;
  boolean nullable;
}
{
  name = SimpleIdentifier()
  type = DataType()
  nullable = NullableOptDefaultTrue() {
    return new SqlReturnField(getPos(), name, new SqlComplexDataTypeSpec(type.withNullable(nullable)));
  }
}

SqlNodeList ParseFunctionFieldList() :
{
  SqlNodeList fieldList = new SqlNodeList(getPos());
}
{
  <LPAREN>
    FieldFunctionTypeCommaList(fieldList.getList())
  <RPAREN>
  {
    return fieldList;
  }
}

SqlNodeList FunctionKeyValuePair() :
{
  SqlNodeList pair = new SqlNodeList(getPos());
  SqlIdentifier name;
  SqlDataTypeSpec type;
  boolean nullable;
  SqlNode defaultExpression = null;
}
{
  name = SimpleIdentifier() { pair.add(name); }
  type = DataType()
  nullable = NullableOptDefaultTrue()
  [ <DEFAULT_> { defaultExpression = OrderedQueryOrExpr(ExprContext.ACCEPT_ALL); } ]
  {
    pair.add(new SqlComplexDataTypeSpecWithDefault(type.withNullable(nullable), defaultExpression));
    return pair;
  }
}

void FieldFunctionTypeCommaList(List<SqlNode> list) :
{
  SqlNodeList pair;
}
{
  (
    pair = FunctionKeyValuePair() { list.add(pair); }
    (
      <COMMA> pair = FunctionKeyValuePair() {
        list.add(pair);
      }
    )*
  )?
}

/**
 * Parses a drop view or drop view if exists statement.
 * DROP VIEW [IF EXISTS] view_name;
 */
SqlNode SqlDropView() :
{
    SqlParserPos pos;
    boolean shouldErrorIfViewDoesNotExist = true;
    SqlIdentifier viewName = null;
    ReferenceType refType = null;
    SqlIdentifier refValue = null;
}
{
    <DROP> { pos = getPos(); }
    ( <VIEW> | <VDS> )
    [ <IF> <EXISTS> { shouldErrorIfViewDoesNotExist = false; } ]
    viewName = CompoundIdentifier()
    [
      <AT>
      (
        <REF> { refType = ReferenceType.REFERENCE; }
        |
        <REFERENCE> { refType = ReferenceType.REFERENCE; }
        |
        <BRANCH> { refType = ReferenceType.BRANCH; }
      )
      { refValue = SimpleIdentifier(); }
    ]
    {
        return new SqlDropView(pos, viewName, shouldErrorIfViewDoesNotExist, refType, refValue);
    }
}

SqlNodeList TableElementListWithMasking() :
{
    final Span s;
    final List<SqlNode> list = new ArrayList<SqlNode>();
}
{
    <LPAREN> { s = span(); }
    TableElementWithMasking(list)
    (
        <COMMA> TableElementWithMasking(list)
    )*
    <RPAREN> {
        return new SqlNodeList(list, s.end(this));
    }
}

SqlNodeList TableElementList() :
{
    final Span s;
    final List<SqlNode> list = new ArrayList<SqlNode>();
}
{
    <LPAREN> { s = span(); }
    TableElement(list)
    (
        <COMMA> TableElement(list)
    )*
    <RPAREN> {
        return new SqlNodeList(list, s.end(this));
    }
}

void TableElementWithMasking(List<SqlNode> list) :
{
    final SqlIdentifier id;
    final SqlDataTypeSpec type;
    final boolean nullable;
    final SqlNode e;
    final SqlNode constraint;
    SqlIdentifier name = null;
    final SqlNodeList columnList;
    final Span s = Span.of();
    SqlPolicy policy = null;
}
{
    LOOKAHEAD(2) id = SimpleIdentifier()
    (
        type = DataType()
        nullable = NullableOptDefaultTrue()
        (
          <MASKING> <POLICY>  policy = Policy()
          {
              list.add(
                      new DremioSqlColumnDeclaration(s.add(id).end(this), new SqlColumnPolicyPair(id.getParserPosition(), id, policy),
                      new SqlComplexDataTypeSpec(type.withNullable(nullable)), null));
          }
          |
          {
              list.add(
                      new DremioSqlColumnDeclaration(s.add(id).end(this), new SqlColumnPolicyPair(id.getParserPosition(), id, null),
                      new SqlComplexDataTypeSpec(type.withNullable(nullable)), null));
          }
        )
        |
        (
          <MASKING> <POLICY>  policy = Policy()
          {
            list.add(new SqlColumnPolicyPair(id.getParserPosition(), id, policy));
          }
          |
          {
            list.add(new SqlColumnPolicyPair(id.getParserPosition(), id, null));
          }
        )
    )
    |
    (
      id = SimpleIdentifier()
      (
          <MASKING> <POLICY>  policy = Policy()
          {
            list.add(new SqlColumnPolicyPair(id.getParserPosition(), id, policy));
          }
          |
          {
            list.add(new SqlColumnPolicyPair(id.getParserPosition(), id, null));
          }
      )
    )
}

void TableElement(List<SqlNode> list) :
{
    final SqlIdentifier id;
    final SqlDataTypeSpec type;
    final boolean nullable;
    final SqlNode e;
    final SqlNode constraint;
    SqlIdentifier name = null;
    final SqlNodeList columnList;
    final Span s = Span.of();
}
{
    LOOKAHEAD(2) id = SimpleIdentifier()
    (
        type = DataType()
        nullable = NullableOptDefaultTrue()
        {
            list.add(
                    new DremioSqlColumnDeclaration(s.add(id).end(this), new SqlColumnPolicyPair(id.getParserPosition(), id, null),
                    new SqlComplexDataTypeSpec(type.withNullable(nullable)), null));
        }
        |
        { list.add(id); }
    )
    |
    id = SimpleIdentifier() {
        list.add(id);
    }
}

/**
 * Parses a partition transform:
 *
 *   partition_transform:
 *       <column_name>
 *     | <transform_name> ( [ <literal_arg>, ... ] <column_name> )
 */
SqlPartitionTransform ParsePartitionTransform() :
{
    SqlIdentifier id;
    SqlIdentifier columnName;
    List<SqlLiteral> argList = new ArrayList<SqlLiteral>();
    SqlNode arg;
    Span s;
    Token token;
}
{
    (
        id = SimpleIdentifier() { s = span(); }
        [
            <LPAREN>
            (
                arg = Literal() {
                    argList.add((SqlLiteral) arg);
                }
                <COMMA>
            )*
            columnName = SimpleIdentifier()
            <RPAREN> {
                return new SqlPartitionTransform(columnName, id, argList, s.end(this));
            }
        ]
        {
            return new SqlPartitionTransform(id, id.getParserPosition());
        }
    |
        (token = <YEAR> | token = <MONTH> | token = <HOUR> | token = <DAY> | token = <TRUNCATE> | token = <IDENTITY>) {
            s = span();
            id = new SqlIdentifier(token.toString(), getPos());
        }
        <LPAREN>
        (
            arg = Literal() {
                argList.add((SqlLiteral) arg);
            }
            <COMMA>
        )*
        columnName = SimpleIdentifier()
        <RPAREN> {
            return new SqlPartitionTransform(columnName, id, argList, s.end(this));
        }
    )
}

/** Parses a partition transform list */
SqlNodeList ParsePartitionTransformList() :
{
    SqlNodeList transformList = new SqlNodeList(getPos());
    SqlPartitionTransform transform;
}
{
    <LPAREN>
    transform = ParsePartitionTransform() {
        transformList.add(transform);
    }
    (
        <COMMA> transform = ParsePartitionTransform() {
            transformList.add(transform);
        }
    )*
    <RPAREN> {
        return transformList;
    }
}

/** Parses a table property */
void ParseTableProperty(SqlNodeList tablePropertyNameList, SqlNodeList tablePropertyValueList) :
{
    SqlNode name;
    SqlNode value;
}
{
    name = StringLiteral() { tablePropertyNameList.add(name); }
    <EQ> value = StringLiteral() { tablePropertyValueList.add(value); }
}

/**
 * Parses a CTAS statement.
 * CREATE TABLE tblname [ (field1, field2, ...) ]
 *       [ (STRIPED, HASH, ROUNDROBIN) PARTITION BY (field1, field2, ..) ]
 *       [ DISTRIBUTE BY (field1, field2, ..) ]
 *       [ LOCALSORT BY (field1, field2, ..) ]
 *       [ CLUSTER BY (field1, field2, ..) ]
 *       [ TBLPROPERTIES ('property_name' = 'property_value', ...) ]
 *       [ STORE AS (opt1 => val1, opt2 => val3, ...) ]
 *       [ LOCATION location]
 *       [ WITH SINGLE WRITER ]
 *       [ AS select_statement. ]
 */
SqlNode SqlCreateTable() :
{
    SqlParserPos pos;
    SqlIdentifier tblName;
    SqlNodeList fieldList;
    List<SqlNode> formatList = new ArrayList();
    SqlNodeList formatOptions;
    SqlNode location = null;
    PartitionDistributionStrategy partitionDistributionStrategy;
    SqlNodeList partitionTransformList;
    SqlNodeList distributeFieldList;
    SqlNodeList sortFieldList;
    SqlNodeList clusterKeyList;
    SqlLiteral singleWriter;
    SqlNode query;
    boolean ifNotExists = false;
    SqlPolicy policy = null;
    SqlNodeList tablePropertyNameList;
    SqlNodeList tablePropertyValueList;
    SqlTableVersionSpec sqlTableVersionSpec;
}
{
    {
        partitionTransformList = SqlNodeList.EMPTY;
        distributeFieldList = SqlNodeList.EMPTY;
        sortFieldList =  SqlNodeList.EMPTY;
        clusterKeyList =  SqlNodeList.EMPTY;
        formatOptions = SqlNodeList.EMPTY;
        location = null;
        singleWriter = SqlLiteral.createBoolean(false, SqlParserPos.ZERO);
        partitionDistributionStrategy = PartitionDistributionStrategy.UNSPECIFIED;
        fieldList = SqlNodeList.EMPTY;
        policy = null;
        tablePropertyNameList = SqlNodeList.EMPTY;
        tablePropertyValueList = SqlNodeList.EMPTY;
        sqlTableVersionSpec = SqlTableVersionSpec.NOT_SPECIFIED ;
    }
    <CREATE> { pos = getPos(); }
    <TABLE>
    [ <IF> <NOT> <EXISTS> { ifNotExists = true; } ]
    tblName = CompoundIdentifier()
    [ fieldList = TableElementListWithMasking() ]
    [ sqlTableVersionSpec = WriteableAtVersionSpec() ]
    (
        (
            <STRIPED> {
                partitionDistributionStrategy = PartitionDistributionStrategy.STRIPED;
            }
        |
            <HASH> {
                partitionDistributionStrategy = PartitionDistributionStrategy.HASH;
            }
        |
            <ROUNDROBIN> {
                partitionDistributionStrategy = PartitionDistributionStrategy.ROUND_ROBIN;
            }
        )?
        <PARTITION> <BY>
        partitionTransformList = ParsePartitionTransformList()
    )?
    (   <DISTRIBUTE> <BY>
        distributeFieldList = ParseRequiredFieldList("Distribution")
    )?
    (   <LOCALSORT> <BY>
        sortFieldList = ParseRequiredFieldList("Sort")
    )?
    (   <CLUSTER> <BY>
        clusterKeyList = ParseRequiredFieldList("Cluster")
    )?
    (   <TBLPROPERTIES>
        <LPAREN>
            {
                tablePropertyNameList = new SqlNodeList(getPos());
                tablePropertyValueList = new SqlNodeList(getPos());
            }
            ParseTableProperty(tablePropertyNameList, tablePropertyValueList)
            (
                <COMMA>
                ParseTableProperty(tablePropertyNameList, tablePropertyValueList)
            )*
        <RPAREN>
    )?
    (
            <LOCATION> { location = StringLiteral(); }
    )?
    [
        <STORE> <AS>
        <LPAREN>
            Arg0(formatList, ExprContext.ACCEPT_CURSOR)
            (
                <COMMA>
                Arg(formatList, ExprContext.ACCEPT_CURSOR)
            )*
        <RPAREN>
        {
            formatOptions = new SqlNodeList(formatList, getPos());
        }
    ]
    [
        <WITH><SINGLE><WRITER>
        {
            singleWriter = SqlLiteral.createBoolean(true, getPos());
        }
    ]
    [
        <ROW><ACCESS><POLICY>
        {
            policy = Policy();
        }
    ]
        (
            (
                <AS>
                query = OrderedQueryOrExpr(ExprContext.ACCEPT_QUERY)
                {
                    return new SqlCreateTable(pos, tblName, fieldList, ifNotExists, partitionDistributionStrategy,
                        partitionTransformList, formatOptions, location, singleWriter, sortFieldList,
                        distributeFieldList, policy, query, tablePropertyNameList, tablePropertyValueList, sqlTableVersionSpec, clusterKeyList);
                }
            )
            |
            (
                {
                    return new SqlCreateEmptyTable(pos, tblName, fieldList, ifNotExists, partitionDistributionStrategy,
                        partitionTransformList, formatOptions, location, singleWriter, sortFieldList,
                        distributeFieldList, policy, tablePropertyNameList, tablePropertyValueList, sqlTableVersionSpec, clusterKeyList);
                }
            )
        )
}

/**
* Parses a insert table or drop table if exists statement.
* INSERT INTO table_name
* [AT BRANCH | REF | REFERENCE <reference_name>] select_statement;
*/
SqlNode SqlInsertTable() :
{
  SqlParserPos pos;
  SqlIdentifier tblName;
  SqlNode query;
  SqlNodeList fieldList;
  SqlTableVersionSpec sqlTableVersionSpec = SqlTableVersionSpec.NOT_SPECIFIED ;
}
{
  {
    fieldList = SqlNodeList.EMPTY;
  }

  <INSERT> { pos = getPos(); }
  <INTO>
    tblName = CompoundIdentifier()
    [ sqlTableVersionSpec = WriteableAtVersionSpec() ]
    [ fieldList = TableElementList() ]
    query = OrderedQueryOrExpr(ExprContext.ACCEPT_QUERY)
    {
      return new SqlInsertTable(pos, tblName, query, fieldList, sqlTableVersionSpec);
    }
}

/**
* Parses a delete from table
* DELETE FROM targetTable
* [ AT BRANCH refValue ]
* [ WHERE condition ];
*/
SqlNode SqlDeleteFromTable() :
{
  SqlParserPos pos;
  SqlIdentifier targetTable;
  SqlIdentifier alias = null;
  SqlNode sourceTableRef = null;
  SqlNode condition;
  SqlTableVersionSpec sqlTableVersionSpec = SqlTableVersionSpec.NOT_SPECIFIED ;
}
{
  <DELETE> { pos = getPos(); }
  <FROM>
    targetTable = CompoundIdentifier()
    [ sqlTableVersionSpec = WriteableAtVersionSpec() ]
    [ [ <AS> ] alias = SimpleIdentifier() ]
    [ <USING> sourceTableRef = FromClause() ]
    condition = WhereOpt()
    {
      return new SqlDeleteFromTable(pos, targetTable, condition, alias, sourceTableRef, sqlTableVersionSpec);
    }
}

/**
* Parses a update table statement
* UPDATE targetTable
* [ AT BRANCH refValue ]
* SET <id> = <exp> [, <id> = <exp> ... ]
* [ WHERE condition ];
*/
SqlNode SqlUpdateTable() :
{
  SqlIdentifier targetTable;
  SqlIdentifier alias = null;
  SqlNode sourceTableRef = null;
  SqlNodeList targetColumnList;
  SqlNodeList sourceExpressionList;
  SqlNode condition;
  SqlIdentifier id;
  SqlNode exp;
  final Span s;
  SqlTableVersionSpec sqlTableVersionSpec = SqlTableVersionSpec.NOT_SPECIFIED ;
}
{
  <UPDATE> { s = span(); }
  targetTable = CompoundIdentifier() {
      targetColumnList = new SqlNodeList(s.pos());
      sourceExpressionList = new SqlNodeList(s.pos());
  }
  [ sqlTableVersionSpec = WriteableAtVersionSpec() ]
  [ [ <AS> ] alias = SimpleIdentifier() ]
  <SET> id = SimpleIdentifier() {
      targetColumnList.add(id);
  }
  <EQ> exp = Expression(ExprContext.ACCEPT_SUB_QUERY) {
      sourceExpressionList.add(exp);
  }
  (
      <COMMA>
      id = SimpleIdentifier()
      {
          targetColumnList.add(id);
      }
      <EQ> exp = Expression(ExprContext.ACCEPT_SUB_QUERY)
      {
          sourceExpressionList.add(exp);
      }
  )*
  [ <FROM> sourceTableRef = FromClause() ]
  condition = WhereOpt()
  {
      return new SqlUpdateTable(
          s.addAll(targetColumnList).addAll(sourceExpressionList).addIf(condition).pos(),
          targetTable,
          targetColumnList,
          sourceExpressionList,
          condition,
          alias,
          sourceTableRef,
          sqlTableVersionSpec);
  }
}

/**
 * Parses a MERGE statement.
 */
SqlNode SqlMergeIntoTable() :
{
    SqlIdentifier table;
    SqlIdentifier alias = null;
    SqlNode sourceTableRef;
    SqlNode condition;
    SqlUpdate updateCall = null;
    SqlInsert insertCall = null;
    final Span s;
    SqlTableVersionSpec targetSqlTableVersionSpec = SqlTableVersionSpec.NOT_SPECIFIED ;
}
{
    <MERGE> { s = span(); } <INTO> table = CompoundIdentifier()
    [ targetSqlTableVersionSpec = WriteableAtVersionSpec() ]
    [ [ <AS> ] alias = SimpleIdentifier() ]
    <USING> sourceTableRef = TableRef()
    <ON> condition = Expression(ExprContext.ACCEPT_SUB_QUERY)
    (
        LOOKAHEAD(2)
        updateCall = DremioWhenMatchedClause(table, alias)
        [ insertCall = DremioWhenNotMatchedClause(table) ]
    |
        insertCall = DremioWhenNotMatchedClause(table)
    )
    {
        return new SqlMergeIntoTable(s.addIf(updateCall).addIf(insertCall).pos(), table,
            condition, sourceTableRef, updateCall, insertCall, alias, targetSqlTableVersionSpec);
    }
}

/**
 * Dremio version of WhenMatchedClause.
 * It returns Dremio's SqlUpdateTable, which contains extended system columns (i.e., filePath and rowIndex)
 */
SqlUpdate DremioWhenMatchedClause(SqlNode table, SqlIdentifier alias) :
{
    SqlIdentifier id;
    final Span s;
    final SqlNodeList updateColumnList = new SqlNodeList(SqlParserPos.ZERO);
    SqlNode exp;
    final SqlNodeList updateExprList = new SqlNodeList(SqlParserPos.ZERO);
    SqlNode updateSource = null;
}
{
    <WHEN> { s = span(); } <MATCHED> <THEN>
    <UPDATE> <SET>
    (
        <STAR> { updateSource = SqlIdentifier.star(getPos()); }
   |
        id = SimpleIdentifier() {
            updateColumnList.add(id);
        }
        <EQ> exp = Expression(ExprContext.ACCEPT_SUB_QUERY) {
            updateExprList.add(exp);
        }
        (
            <COMMA>
            id = SimpleIdentifier() {
                updateColumnList.add(id);
            }
            <EQ> exp = Expression(ExprContext.ACCEPT_SUB_QUERY) {
                updateExprList.add(exp);
            }
        )*
     )
    {
        return new SqlUpdateTable(s.addAll(updateExprList).pos(), table,
            updateColumnList, updateExprList, null, alias, updateSource, null);
    }
}

/**
 * Dremio version of DremioWhenNotMatchedClause.
 * It returns Dremio's SqlInsertTable, which contains extended system columns (i.e., filePath and rowIndex)
 */
SqlInsert DremioWhenNotMatchedClause(SqlIdentifier table) :
{
    final Span insertSpan, valuesSpan;
    final List<SqlLiteral> keywords = new ArrayList<SqlLiteral>();
    final SqlNodeList keywordList;
    SqlNodeList insertColumnList = null;
    SqlNode rowConstructor;
    SqlNode insertSource;
}
{
    <WHEN> <NOT> <MATCHED> <THEN> <INSERT> {
        insertSpan = span();
    }
    SqlInsertKeywords(keywords) {
        keywordList = new SqlNodeList(keywords, insertSpan.end(this));
    }
    (
        <STAR> { insertSource = SqlIdentifier.star(getPos()); }
    |
        [
            LOOKAHEAD(2)
            insertColumnList = ParenthesizedSimpleIdentifierList()
        ]
        [ <LPAREN> ]
        <VALUES> { valuesSpan = span(); }
        rowConstructor = RowConstructor()
        [ <RPAREN> ]
        {
            // TODO zfong 5/26/06: note that extra parentheses are accepted above
            // around the VALUES clause as a hack for unparse, but this is
            // actually invalid SQL; should fix unparse
            insertSource = SqlStdOperatorTable.VALUES.createCall(
                valuesSpan.end(this), rowConstructor);
        }
    )
    {
        return new SqlInsertTable(insertSpan.end(this),  table, insertSource, insertColumnList, null);
    }
}

/**
 * Parses a drop table or drop table if exists statement.
 * DROP TABLE [IF EXISTS] table_name;
 * [ AT ( REF[ERENCE) | BRANCH ) refValue ]
 */
SqlNode SqlDropTable() :
{
    SqlParserPos pos;
    SqlIdentifier tblName;
    boolean shouldErrorIfTableDoesNotExist = true;
    ReferenceType refType = null;
    SqlIdentifier refValue = null;
}
{
    <DROP> { pos = getPos(); }
    <TABLE>
    [ <IF> <EXISTS> { shouldErrorIfTableDoesNotExist = false; } ]
    tblName = CompoundIdentifier()
    [
      <AT>
      (
        <REF> { refType = ReferenceType.REFERENCE; }
        |
        <REFERENCE> { refType = ReferenceType.REFERENCE; }
        |
        <BRANCH> { refType = ReferenceType.BRANCH; }
      )
      { refValue = SimpleIdentifier(); }
    ]
    {
        return new SqlDropTable(pos, tblName, shouldErrorIfTableDoesNotExist, refType, refValue);
    }
}

/**
 * Parses a Rollback table statement.
 * ROLLBACK TABLE <table_name>
 * TO [ SNAPSHOT <snapshot>] | [ TIMESTAMP <timestamp>]
 */
SqlNode SqlRollbackTable() :
{
    SqlParserPos pos;
    SqlIdentifier table;
    SqlNode specifier;
    boolean snapshotKeywordPresent = false;
}
{
    <ROLLBACK> { pos = getPos(); }
    <TABLE>
    { table = CompoundIdentifier(); }
    <TO>
    (
        <SNAPSHOT>
        {
            snapshotKeywordPresent = true;
            specifier = StringLiteral();
        }
    |
        <TIMESTAMP> { specifier = StringLiteral(); }
    )
    {
        return new SqlRollbackTable(pos, table, snapshotKeywordPresent, specifier);
    }
}

 /**
 * Parses a VACUUM statement.
 * VACUUM TABLE <name>
 * EXPIRE SNAPSHOTS [older_than [=] <value>] [retain_last [=] <value>]
 */
SqlNode SqlVacuum() :
{
    SqlParserPos pos;
}
{
    <VACUUM> { pos = getPos(); }
    (
        <CATALOG>
        {
          return SqlVacuumCatalog(pos);
        }
        |
        <TABLE>
        {
          return SqlVacuumTable(pos);
        }
    )
}

void VacuumTableExpireSnapshotOptions(SqlNodeList optionsList, SqlNodeList optionsValueList) :
{
    SqlNode exp;
}
{
    [
        <OLDER_THAN> [<EQ>] exp = StringLiteral()
        {
            optionsList.add(new SqlIdentifier("older_than", getPos()));
            optionsValueList.add(exp);
        }
    ]
    [
        <RETAIN_LAST> [<EQ>] exp = UnsignedNumericLiteral()
        {
            optionsList.add(new SqlIdentifier("retain_last", getPos()));
            optionsValueList.add(exp);
        }
    ]
}

void VacuumTableRemoveOrphanFilesOptions(SqlNodeList optionsList, SqlNodeList optionsValueList) :
{
    SqlNode exp;
}
{
    [
        <OLDER_THAN> [<EQ>] exp = StringLiteral()
        {
            optionsList.add(new SqlIdentifier("older_than", getPos()));
            optionsValueList.add(exp);
        }
    ]
    [
        <LOCATION> [<EQ>] exp = StringLiteral()
        {
            optionsList.add(new SqlIdentifier("location", getPos()));
            optionsValueList.add(exp);
        }
    ]
}

SqlNode SqlVacuumTable(SqlParserPos pos) :
{
    SqlIdentifier tableName;
    SqlNodeList optionsList = new SqlNodeList(getPos());
    SqlNodeList optionsValueList = new SqlNodeList(getPos());
    SqlLiteral expireSnapshots = SqlLiteral.createBoolean(true, SqlParserPos.ZERO);
    SqlLiteral removeOrphans = SqlLiteral.createBoolean(false, SqlParserPos.ZERO);
    SqlNode exp;
}
{
{
tableName = CompoundIdentifier();
}
    (
       (
            <EXPIRE> <SNAPSHOTS>
            {
                expireSnapshots = SqlLiteral.createBoolean(true, SqlParserPos.ZERO);
                removeOrphans = SqlLiteral.createBoolean(false, SqlParserPos.ZERO);
            }
            VacuumTableExpireSnapshotOptions(optionsList, optionsValueList)
       )
       |
       (
           <REMOVE> <ORPHAN> <FILES>
           {
                expireSnapshots = SqlLiteral.createBoolean(false, SqlParserPos.ZERO);
                removeOrphans = SqlLiteral.createBoolean(true, SqlParserPos.ZERO);
            }
           VacuumTableRemoveOrphanFilesOptions(optionsList, optionsValueList)
       )
    )
    {
        return new SqlVacuumTable(pos, tableName, expireSnapshots, removeOrphans, optionsList, optionsValueList);
    }
}

SqlNode SqlVacuumCatalog(SqlParserPos pos) :
{
    SqlIdentifier catalogSource;
    SqlNodeList excludeTableList = new SqlNodeList(getPos());
}
{
    catalogSource = SimpleIdentifier()
    [
        <EXCLUDE>
        <LPAREN>
            TableWithVersionContextCommaList(excludeTableList)
        <RPAREN>
    ]
    {
        return new SqlVacuumCatalog(pos, catalogSource, excludeTableList);
    }
}

void TableWithVersionContextCommaList(SqlNodeList tableList) :
{
    SqlNode tableWithVersionContext = null;
}
{
    tableWithVersionContext = CompoundIdentifier()
    [ tableWithVersionContext = TableWithVersionContext(tableWithVersionContext) ]
    { tableList.add(tableWithVersionContext); }
    (
        <COMMA> tableWithVersionContext = CompoundIdentifier()
        [ tableWithVersionContext = TableWithVersionContext(tableWithVersionContext) ]
        { tableList.add(tableWithVersionContext); }
    )*
}

/**
 * Parses a truncate table statement.
 * TRUNCATE [TABLE] [IF EXISTS] table_name;
 * [AT BRANCH <reference_name>]
 */
SqlNode SqlTruncateTable() :
{
    SqlParserPos pos;
    boolean shouldErrorIfTableDoesNotExist = true;
    boolean tableKeywordPresent = false;
    SqlIdentifier tableName;
    ReferenceType refType = null;
    SqlIdentifier refValue = null;}
{
    <TRUNCATE> { pos = getPos(); }
    [ <TABLE> { tableKeywordPresent = true; } ]
    [ <IF> <EXISTS> { shouldErrorIfTableDoesNotExist = false; } ]
    tableName = CompoundIdentifier()
    [
      <AT>
      <BRANCH>
      { refType = ReferenceType.BRANCH; }
      { refValue = SimpleIdentifier(); }
    ]
    {
        return new SqlTruncateTable(pos, shouldErrorIfTableDoesNotExist, tableKeywordPresent, tableName, refType, refValue);
    }
}

/**
 * Parses a $REFRESH REFLECTION statement
 *   $REFRESH REFLECTION reflectionId AS materializationId
 */
SqlNode SqlRefreshReflection() :
{
    SqlParserPos pos;
    SqlNode reflectionId;
    SqlNode materializationId;
}
{
    <REFRESH> { pos = getPos(); }
    <REFLECTION>
    { reflectionId = StringLiteral(); }
    <AS>
    { materializationId = StringLiteral(); }
    {
        return new SqlRefreshReflection(pos, reflectionId, materializationId);
    }
}

/**
 * Parses a ANALYZE TABLE STATISTICS
 */
SqlNode SqlAnalyzeTableStatistics() :
{
    final SqlParserPos pos;
    final SqlIdentifier table;
    SqlLiteral isAnalyze = SqlLiteral.createBoolean(true, SqlParserPos.ZERO);
    SqlNodeList columns = SqlNodeList.EMPTY;
}
{
    <ANALYZE> { pos = getPos(); }
    <TABLE> { table = CompoundIdentifier(); }
    <FOR>
      (
        <ALL> <COLUMNS> { columns = SqlNodeList.EMPTY; }
        |
        <COLUMNS> { columns = ParseOptionalFieldList("Columns"); }
      )
    (
      <COMPUTE> <STATISTICS> { isAnalyze = SqlLiteral.createBoolean(true, SqlParserPos.ZERO); }
      |
      <DELETE> <STATISTICS> { isAnalyze = SqlLiteral.createBoolean(false, SqlParserPos.ZERO); }
    )
    { return new SqlAnalyzeTableStatistics(pos, table, isAnalyze, columns); }
}

/**
 * Parses a REFRESH DATASET table_name statement
 */
SqlNode SqlRefreshDataset() :
{
    SqlParserPos pos;
    SqlIdentifier tblName;
    SqlLiteral deleteUnavail = SqlLiteral.createNull(SqlParserPos.ZERO);
    SqlLiteral promotion = SqlLiteral.createNull(SqlParserPos.ZERO);
    SqlLiteral forceUp = SqlLiteral.createNull(SqlParserPos.ZERO);
    SqlLiteral allFilesRefresh = SqlLiteral.createNull(SqlParserPos.ZERO);
    SqlLiteral allPartitionsRefresh = SqlLiteral.createNull(SqlParserPos.ZERO);
    SqlLiteral fileRefresh = SqlLiteral.createNull(SqlParserPos.ZERO);
    SqlLiteral partitionRefresh = SqlLiteral.createNull(SqlParserPos.ZERO);
    SqlNodeList filesList = SqlNodeList.EMPTY;
    SqlNodeList partitionList = SqlNodeList.EMPTY;
    SqlNode fileNameRegex = SqlLiteral.createNull(SqlParserPos.ZERO);
    SqlLiteral errorOnConcurrentRefresh = SqlLiteral.createNull(SqlParserPos.ZERO);
}
{
    <REFRESH> { pos = getPos(); }
    <DATASET>
    { tblName = CompoundIdentifier(); }
    (
      <FOR> <ALL>
      (
        <FILES> { allFilesRefresh = SqlLiteral.createBoolean(true, pos); }
      |
        <PARTITIONS> { allPartitionsRefresh = SqlLiteral.createBoolean(true, pos); }
      )
    |
      <FOR> <FILES> {
        fileRefresh = SqlLiteral.createBoolean(true, pos);
        filesList = ParseRequiredFilesList();
      }
    |
      <FOR> <PARTITIONS> {
        partitionRefresh = SqlLiteral.createBoolean(true, pos);
        partitionList = ParseRequiredPartitionList();
      }
    |
      <FOR> <REGEX> {
        fileNameRegex = StringLiteral();
      }
    )?
    (
      <AUTO> <PROMOTION> { promotion = SqlLiteral.createBoolean(true, pos); }
    |
      <AVOID> <PROMOTION> { promotion = SqlLiteral.createBoolean(false, pos); }
    )?
    (
      <FORCE> <UPDATE> { forceUp = SqlLiteral.createBoolean(true, pos); }
    |
      <LAZY> <UPDATE> { forceUp = SqlLiteral.createBoolean(false, pos); }
    )?
    (
      <DELETE> <WHEN> <MISSING> { deleteUnavail = SqlLiteral.createBoolean(true, pos); }
    |
      <MAINTAIN> <WHEN> <MISSING> { deleteUnavail = SqlLiteral.createBoolean(false, pos); }
    )?
    (
      <ERROR> <ON> <CONCURRENT> <REFRESH> { errorOnConcurrentRefresh = SqlLiteral.createBoolean(true, pos); }
    )?
    { return new SqlRefreshDataset(pos, tblName, deleteUnavail, forceUp, promotion, allFilesRefresh,
        allPartitionsRefresh, fileRefresh, partitionRefresh, filesList, partitionList,
        fileNameRegex, errorOnConcurrentRefresh); }
}

/**
 * Parses an OPTIMIZE TABLE <table_name>
             [  REWRITE MANIFESTS ]
             |[  REWRITE DATA USING BIN_PACK| SORT
                [ ( option = <value> [, ... ] ) ]
                [ FOR PARTITIONS <predicate> ] ]
 */
SqlNode SqlOptimize() :
{
    final SqlParserPos pos;
    final SqlIdentifier table;
    SqlLiteral rewriteManifests = SqlLiteral.createBoolean(true, SqlParserPos.ZERO);
    SqlLiteral rewriteDataFiles = SqlLiteral.createBoolean(true, SqlParserPos.ZERO);
    CompactionType compactionType = CompactionType.BIN_PACK;
    SqlNode sortOrderId = null;
    SqlNode condition = null;
    SqlNodeList optionsList = null;
    SqlNodeList optionsValueList = null;
}
{
    <OPTIMIZE> { pos = getPos(); }
    <TABLE> { table = CompoundIdentifier(); }
    (
      <REWRITE> <MANIFESTS>
      {
        rewriteManifests = SqlLiteral.createBoolean(true, pos);
        rewriteDataFiles = SqlLiteral.createBoolean(false, pos);
      }
    |
      [
        <REWRITE> <DATA>
        {
          rewriteManifests = SqlLiteral.createBoolean(false, pos);
          rewriteDataFiles = SqlLiteral.createBoolean(true, pos);
        }
      ]
      [
        <USING> <BIN_PACK> { compactionType = CompactionType.BIN_PACK; }
      ]
      [ <FOR> <PARTITIONS> { condition = Expression(ExprContext.ACCEPT_SUB_QUERY); } ]
      [
         <LPAREN>
         {
           optionsList = new SqlNodeList(getPos());
           optionsValueList = new SqlNodeList(getPos());
         }
         ParseOptimizeOptions(optionsList, optionsValueList)
         (
           <COMMA>
           ParseOptimizeOptions(optionsList, optionsValueList)
         )*
         <RPAREN>
      ]
    )
    { return new SqlOptimize(pos, table, rewriteManifests, rewriteDataFiles, compactionType, condition, optionsList, optionsValueList); }
}

/**
 * Parse options for OPTIMIZE TABLE command.
 */
 void ParseOptimizeOptions(SqlNodeList optionsList, SqlNodeList optionsValueList) :
 {
  SqlNode exp;
 }
 {
    (
      <MIN_INPUT_FILES> { optionsList.add( new SqlIdentifier("min_input_files", getPos())); }
      |
      <TARGET_FILE_SIZE_MB> { optionsList.add( new SqlIdentifier("target_file_size_mb", getPos())); }
      |
      <MIN_FILE_SIZE_MB> { optionsList.add( new SqlIdentifier("min_file_size_mb", getPos())); }
      |
      <MAX_FILE_SIZE_MB> { optionsList.add( new SqlIdentifier("max_file_size_mb", getPos())); }
    )
    <EQ> exp = Literal() {
        optionsValueList.add(exp);
    }
 }

/**
 * Parse a "name1  type1 [NULL | NOT NULL], name2 type2 [NULL | NOT NULL] ..." list,
 * the field type default is nullable.
 */
 void FieldNameStructTypeCommaList(
         List<SqlIdentifier> fieldNames,
         List<SqlComplexDataTypeSpec> fieldTypes) :
 {
     SqlIdentifier fName;
     SqlDataTypeSpec fType;
     boolean nullable;
 }
 {
     fName = SimpleIdentifier()
     (<COLON>)?
     fType = DataType()
     nullable = NullableOptDefaultTrue()
     {
         fieldNames.add(fName);
         fieldTypes.add(new SqlComplexDataTypeSpec(fType.withNullable(nullable)));
     }
     (
         <COMMA>
         fName = SimpleIdentifier()
         (<COLON>)?
         fType = DataType()
         nullable = NullableOptDefaultTrue()
         {
             fieldNames.add(fName);
             fieldTypes.add(new SqlComplexDataTypeSpec(fType.withNullable(nullable)));
         }
     )*
 }

 /**
 * Parse Row type with format: ROW(name1  type1, name2 type2).
 * Parse Row type with format: STRUCT<name1 : type1, name2 : type2>.
 * Every item type can have suffix of `NULL` or `NOT NULL` to indicate if this type is nullable.
 * i.e. ROW(name1  type1 not null, name2 type2 null).
 */
 SqlIdentifier DremioRowTypeName() :
 {
     List<SqlIdentifier> fieldNames = new ArrayList<SqlIdentifier>();
     List<SqlComplexDataTypeSpec> fieldTypes = new ArrayList<SqlComplexDataTypeSpec>();
 }
 {
     (<ROW> | <STRUCT>)
     (<LPAREN> | <LT>) FieldNameStructTypeCommaList(fieldNames, fieldTypes) (<RPAREN> | <GT>)
     {
         return new DremioSqlRowTypeSpec(getPos(), fieldNames, fieldTypes);
     }
 }

 /**
  * Parse Array type with format: ARRAY(data_type).
  * Parse Array type with format: LIST<data_type>.
  * Every item type can have suffix of `NULL` or `NOT NULL` to indicate if this type is nullable.
  * i.e. col1 ARRAY(varchar NOT NULL).
  */
  SqlIdentifier ArrayTypeName() :
  {
      SqlDataTypeSpec fType;
      boolean nullable;
  }
  {
      (<ARRAY> | <LIST>)
      (<LPAREN> | <LT>)
       fType = DataType()
       nullable = NullableOptDefaultTrue()
      (<RPAREN> | <GT>)
      {
          return new SqlArrayTypeSpec(getPos(), new SqlComplexDataTypeSpec(fType.withNullable(nullable)));
      }
  }

 /**
  * Parse Map type with format: MAP(data_type, data_type).
  * Parse Map type with format: MAP<data_type, data_type>.
  * Key and value type can have suffix of `NULL` or `NOT NULL` to indicate if this type is nullable.
  */
  SqlIdentifier MapTypeName() :
  {
      SqlDataTypeSpec fTypeKey;
      SqlDataTypeSpec fTypeValue;
      boolean nullable;
  }
  {
      (<MAP>)
      (<LPAREN> | <LT>)
       fTypeKey = DataType()
      <COMMA>
       fTypeValue = DataType()
       nullable = NullableOptDefaultTrue()
      (<RPAREN> | <GT>)
      {
          return new SqlMapTypeSpec(getPos(),
            new SqlComplexDataTypeSpec(fTypeKey),
            new SqlComplexDataTypeSpec(fTypeValue.withNullable(nullable)));
      }
  }

  /**
   * Parses an EXPLAIN PLAN statement. e.g.
   * EXPLAIN PLAN FOR
   * UPDATE targetTable
   * SET <id> = <exp> [, <id> = <exp> ... ]
   */
  SqlNode SqlExplainQueryDML() :
  {
      SqlNode stmt;
      SqlExplainLevel detailLevel = SqlExplainLevel.EXPPLAN_ATTRIBUTES;
      SqlExplain.Depth depth;
      final SqlExplainFormat format;
  }
  {
      <EXPLAIN> <PLAN>
      [ detailLevel = ExplainDetailLevel() ]
      depth = ExplainDepth()
      (
          <AS> <XML> { format = SqlExplainFormat.XML; }
      |
          <AS> <JSON> { format = SqlExplainFormat.JSON; }
      |
          { format = SqlExplainFormat.TEXT; }
      )
      <FOR> stmt = SqlQueryOrTableDml() {
          return new SqlExplain(getPos(),
              stmt,
              detailLevel.symbol(SqlParserPos.ZERO),
              depth.symbol(SqlParserPos.ZERO),
              format.symbol(SqlParserPos.ZERO),
              nDynamicParams);
      }
  }

  /** Parses a query (SELECT or VALUES)
   * or DML statement (INSERT, UPDATE, DELETE, MERGE). */
  SqlNode SqlQueryOrTableDml() :
  {
      SqlNode stmt;
  }
  {
      (
          stmt = SqlInsertTable()
      |
          stmt = SqlDeleteFromTable()
      |
          stmt = SqlUpdateTable()
      |
          stmt = SqlMergeIntoTable()
      |
          stmt = OrderedQueryOrExpr(ExprContext.ACCEPT_QUERY)
      ) { return stmt; }
  }

/**
 * CREATE FOLDER [ IF NOT EXISTS ] [source.]parentFolderName[.childFolder]
 * [ AT ( REF[ERENCE) | BRANCH | TAG | COMMIT ) refValue ]
 */
SqlNode SqlCreateFolder() :
{
  SqlParserPos pos;
  SqlLiteral ifNotExists = SqlLiteral.createBoolean(false, SqlParserPos.ZERO);
  SqlIdentifier folderName;
  ReferenceType refType = null;
  SqlIdentifier refValue = null;
}
{
  <CREATE> { pos = getPos(); }
  <FOLDER>
  [ <IF> <NOT> <EXISTS> { ifNotExists = SqlLiteral.createBoolean(true, SqlParserPos.ZERO); } ]
  folderName = CompoundIdentifier()
  [
    <AT>
    (
      <REF> { refType = ReferenceType.REFERENCE; }
      |
      <REFERENCE> { refType = ReferenceType.REFERENCE; }
      |
      <BRANCH> { refType = ReferenceType.BRANCH; }
    )
    { refValue = SimpleIdentifier(); }
  ]
  { return new SqlCreateFolder(pos, ifNotExists, folderName, refType, refValue); }
}

/**
 * DROP FOLDER [ IF NOT EXISTS ] [source.]parentFolderName[.childFolder]
 * [ AT BRANCH refValue ]
 */
SqlNode SqlDropFolder() :
{
  SqlParserPos pos;
  SqlLiteral ifNotExists = SqlLiteral.createBoolean(false, SqlParserPos.ZERO);
  SqlIdentifier folderName;
  ReferenceType refType = null;
  SqlIdentifier refValue = null;
}
{
  <DROP> { pos = getPos(); }
  <FOLDER>
  [ <IF> <NOT> <EXISTS> { ifNotExists = SqlLiteral.createBoolean(true, SqlParserPos.ZERO); } ]
  folderName = CompoundIdentifier()
  [
    <AT>
    <BRANCH> { refType = ReferenceType.BRANCH; }
    { refValue = SimpleIdentifier(); }
  ]
  { return new SqlDropFolder(pos, ifNotExists, folderName, refType, refValue); }
}

/**
 *   SHOW CREATE (VIEW | TABLE) datasetName
 *   [ AT ( REF[ERENCE] | BRANCH | TAG | COMMIT ) refValue ]
 */
SqlNode SqlShowCreate() :
{
    SqlParserPos pos;
    SqlIdentifier datasetName = null;
    ReferenceType refType = null;
    SqlIdentifier refValue = null;
    boolean isView;
}
{
    <SHOW> { pos = getPos(); }
    <CREATE>
    (
      <VIEW> { isView = true; }
      |
      <TABLE> { isView = false; }
    )

    datasetName = CompoundIdentifier()
    [
        <AT>
        (
            <REF> { refType = ReferenceType.REFERENCE; }
            |
            <REFERENCE> { refType = ReferenceType.REFERENCE; }
            |
            <BRANCH> { refType = ReferenceType.BRANCH; }
            |
            <TAG> { refType = ReferenceType.TAG; }
            |
            <COMMIT> { refType = ReferenceType.COMMIT; }
        )
        { refValue = SimpleIdentifier(); }
    ]
    {
        return new SqlShowCreate(pos, isView, datasetName, refType, refValue);
    }
}

/**
 * ALTER ENGINE <engine_name> SET
 * [MIN_REPLICAS = <no of replicas>],
 * [MAX_REPLICAS = <no of replicas>]
**/
SqlNode SqlAlterEngine():
{
    SqlParserPos pos;
    SqlIdentifier engineName = null;
    SqlNumericLiteral minReplicas = null;
    SqlNumericLiteral maxReplicas = null;
}
{
    <ALTER> { pos = getPos(); }
    <ENGINE>
    engineName = SimpleIdentifier()
    <SET>
    <LPAREN>
    (
      (
          <MIN_REPLICAS> <EQ> minReplicas = UnsignedNumericLiteral()
          ( <COMMA> <MAX_REPLICAS> <EQ> maxReplicas = UnsignedNumericLiteral() )?
      )
      |
      (
          <MAX_REPLICAS> <EQ> maxReplicas = UnsignedNumericLiteral()
          ( <COMMA> <MIN_REPLICAS> <EQ> minReplicas = UnsignedNumericLiteral() )?
      )
    )?
    <RPAREN>
    {
        return new SqlAlterEngine(pos, engineName, minReplicas, maxReplicas);
    }
}

SqlNode TryConvertFromFunctionCall():
{
    Span s;
    SqlNode e;
    List<SqlNode> args;
    SqlDataTypeSpec dt;
}
{
    <TRY_CONVERT_FROM> { s = span(); }
    <LPAREN> e = Expression(ExprContext.ACCEPT_SUB_QUERY) { args = startList(e); }
    <AS> dt = DataType() { args.add(new SqlComplexDataTypeSpec(dt.withNullable(true))); }
    <RPAREN> {
        return DremioSqlOperatorTable.TRY_CONVERT_FROM.createCall(s.end(this), args);
    }
}
