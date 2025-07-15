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

parser grammar ExprParser;

options{
  output=AST;
  language=Java;
  tokenVocab=ExprLexer;
  backtrack=true;
  memoize=true;
}



@header {
package com.dremio.common.expression.parser;
  
//Explicit import...
import org.antlr.runtime.BitSet;
import java.util.*;
import com.dremio.common.expression.*;
import com.dremio.common.expression.PathSegment.NameSegment;
import com.dremio.common.expression.PathSegment.ArraySegmentInputRef;
import com.dremio.common.expression.PathSegment.ArraySegment;
import com.dremio.common.types.*;
import com.dremio.common.types.TypeProtos.*;
import com.dremio.common.types.TypeProtos.DataMode;
import com.dremio.common.types.TypeProtos.MajorType;
import com.dremio.common.exceptions.ExpressionParsingException;
}

@members{
  private String fullExpression;
  private int tokenPos;

  public static void p(String s){
    System.out.println(s);
  }
  
  @Override    
  public void displayRecognitionError(String[] tokenNames, RecognitionException e) {
	String hdr = getErrorHeader(e);
    String msg = getErrorMessage(e, tokenNames);
    throw new ExpressionParsingException("Expression has syntax error! " + hdr + ":" + msg);
  }
}

parse returns [LogicalExpression e]
  :  expression EOF {
    $e = $expression.e; 
    if(fullExpression == null) fullExpression = $expression.text;
    tokenPos = $expression.start.getTokenIndex();
  }
  ;
 
functionCall returns [LogicalExpression e]
  :  Identifier OParen exprList? CParen {$e = FunctionCallFactory.createExpression($Identifier.text, $exprList.listE);  }
  ;

convertCall returns [LogicalExpression e]
  :  Convert OParen expression Comma String CParen
      { $e = FunctionCallFactory.createConvert($Convert.text, $String.text, $expression.e);}
  ;

castCall returns [LogicalExpression e]
	@init{
  	  List<LogicalExpression> exprs = new ArrayList<LogicalExpression>();
	}  
  :  Cast OParen expression As dataType repeat? CParen 
      {  if ($repeat.isRep!=null && $repeat.isRep.compareTo(Boolean.TRUE)==0)
           $e = FunctionCallFactory.createCast(TypeProtos.MajorType.newBuilder().mergeFrom($dataType.type).setMode(DataMode.REPEATED).build(), $expression.e);
         else
           $e = FunctionCallFactory.createCast($dataType.type, $expression.e);}
  ;

repeat returns [Boolean isRep]
  : Repeat { $isRep = Boolean.TRUE;}
  ;

dataType returns [MajorType type]
	: numType  {$type =$numType.type;}
	| charType {$type =$charType.type;}
	| dateType {$type =$dateType.type;}
	| booleanType {$type =$booleanType.type;}
	;

booleanType returns [MajorType type]
	: BIT { $type = Types.optional(TypeProtos.MinorType.BIT); }
	;

numType returns [MajorType type]
	: INT    { $type = Types.optional(TypeProtos.MinorType.INT); }
	| BIGINT { $type = Types.optional(TypeProtos.MinorType.BIGINT); }
	| FLOAT4 { $type = Types.optional(TypeProtos.MinorType.FLOAT4); }
	| FLOAT8 { $type = Types.optional(TypeProtos.MinorType.FLOAT8); }
	| DECIMAL OParen precision Comma scale CParen { $type = TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.DECIMAL).setMode(DataMode.OPTIONAL).setPrecision($precision.value.intValue()).setScale($scale.value.intValue()).build(); }
	;

charType returns [MajorType type]
	:  VARCHAR typeLen {$type = TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.VARCHAR).setMode(DataMode.OPTIONAL).setWidth($typeLen.length.intValue()).build(); }
	|  VARBINARY typeLen {$type = TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.VARBINARY).setMode(DataMode.OPTIONAL).setWidth($typeLen.length.intValue()).build();}	
	;

precision returns [Integer value]
    : Number {$value = Integer.parseInt($Number.text); }
    ;

scale returns [Integer value]
    : Number {$value = Integer.parseInt($Number.text); }
    ;

dateType returns [MajorType type]
    : DATE { $type = Types.optional(TypeProtos.MinorType.DATE); }
    | TIMESTAMP { $type = Types.optional(TypeProtos.MinorType.TIMESTAMPMILLI); }
    | TIME { $type = Types.optional(TypeProtos.MinorType.TIME); }
    | INTERVALYEAR { $type = Types.optional(TypeProtos.MinorType.INTERVALYEAR); }
    | INTERVALDAY { $type = Types.optional(TypeProtos.MinorType.INTERVALDAY); }
    ;

typeLen returns [Integer length]
    : OParen Number CParen {$length = Integer.parseInt($Number.text);}
    ;

ifStatement returns [LogicalExpression e]
	@init {
	  IfExpression.Builder s = IfExpression.newBuilder();
	}
	@after {
	  $e = s.build();
	}  
  :  i1=ifStat {s.setIfCondition($i1.i); } (elseIfStat { s.setIfCondition($elseIfStat.i); } )* Else expression { s.setElse($expression.e); }End
  ;

ifStat returns [IfExpression.IfCondition i]
  : If e1=expression Then e2=expression { $i = new IfExpression.IfCondition($e1.e, $e2.e); }
  ;
elseIfStat returns [IfExpression.IfCondition i]
  : Else If e1=expression Then e2=expression { $i = new IfExpression.IfCondition($e1.e, $e2.e); }
  ;

caseStatement returns [LogicalExpression e]
  @init {
    CaseExpression.Builder b = CaseExpression.newBuilder();
    List<CaseExpression.CaseConditionNode> exprs = new ArrayList<CaseExpression.CaseConditionNode>();
  }
  @after {
    b.setCaseConditions(exprs);
    $e = b.build();
  }
  : Case (caseWhenStat { exprs.add($caseWhenStat.e); })* caseElseStat { b.setElseExpr($caseElseStat.e); } End
  ;

caseWhenStat returns [CaseExpression.CaseConditionNode e]
  : When e1=expression Then e2=expression {$e = new CaseExpression.CaseConditionNode($e1.e, $e2.e); }
  ;
  
caseElseStat returns [LogicalExpression e]
  : Else expression {$e = $expression.e; }
  ;
  
exprList returns [List<LogicalExpression> listE]
	@init{
	  $listE = new ArrayList<LogicalExpression>();
	}
  :  e1=expression {$listE.add($e1.e); } (Comma e2=expression {$listE.add($e2.e); } )*
  ;

expression returns [LogicalExpression e]  
  :  ifStatement {$e = $ifStatement.e; }
  |  caseStatement {$e = $caseStatement.e; }
  |  condExpr {$e = $condExpr.e; }
  |  listAggExpr {$e = $listAggExpr.e; }
  ;

listAggExpr returns [LogicalExpression e]
	@init{
    ListAggExpression.Builder b = ListAggExpression.newBuilder();
	  List<Ordering> orderings = null;
	  List<LogicalExpression> extraExpressions = null;
	}
	@after{
	  if (orderings == null) {
	    b.setOrderings(new ArrayList<Ordering>());
	  } else {
	    b.setOrderings(orderings);
	  }
	  if (extraExpressions == null) {
	    b.setExtraExpressions(new ArrayList<LogicalExpression>());
	  } else {
	    b.setExtraExpressions(extraExpressions);
	  }
    $e = b.build();
	}
  :  ListAggExp OParen e1=functionCall { b.addFunctionCall($e1.e); } Comma e2=String { b.setIsDistinct($e2.text); }  Comma OParen orderingList? { orderings = $orderingList.listE; } CParen Comma OParen extraExpressionsList? { extraExpressions = $extraExpressionsList.listE; } CParen CParen
  ;

extraExpressionsList returns [List<LogicalExpression> listE]
	@init{
	  $listE = new ArrayList<LogicalExpression>();
	}
  :  e1=String {$listE.add(new ValueExpressions.QuotedString($e1.text )); } (Comma e2=String {$listE.add(new ValueExpressions.QuotedString($e2.text )); } )*
  ;

orderingList returns [List<Ordering> listE]
	@init{
	  $listE = new ArrayList<Ordering>();
	}
  :  e1=ordering {$listE.add($e1.e); } (Comma e2=ordering {$listE.add($e2.e); } )*
  ;

ordering returns [Ordering e]
  : Order OParen e1=expression Comma e2=String Comma e3=String CParen { $e = new Ordering($e1.e, $e2.text, $e3.text); }
  ;

condExpr returns [LogicalExpression e]
  :  orExpr {$e = $orExpr.e; }
  ;

orExpr returns [LogicalExpression e]
	@init{
	  List<LogicalExpression> exprs = new ArrayList<LogicalExpression>();
	}
	@after{
	  if(exprs.size() == 1){
	    $e = exprs.get(0);
	  }else{
	    $e = FunctionCallFactory.createBooleanOperator("or", exprs);
	  }
	}
  :  a1=andExpr { exprs.add($a1.e);} (Or a2=andExpr { exprs.add($a2.e); })*
  ;

andExpr returns [LogicalExpression e]
	@init{
	  List<LogicalExpression> exprs = new ArrayList<LogicalExpression>();
	}
	@after{
	  if(exprs.size() == 1){
	    $e = exprs.get(0);
	  }else{
	    $e = FunctionCallFactory.createBooleanOperator("and", exprs);
	  }
	}
  :  e1=inExpr { exprs.add($e1.e); } ( And e2=inExpr { exprs.add($e2.e);  })*
  ;

inExpr returns [LogicalExpression e]
	@init{
	  List<LogicalExpression> exprs = null;
	}
	@after{
	  if(exprs == null){
	    $e = $a1.e;
	  }else{
	    $e = new InExpression($a1.e, exprs);
	  }
	}
  :  a1=equExpr (In OParen exprList CParen { exprs = $exprList.listE; } )?
  ;

equExpr returns [LogicalExpression e]
	@init{
	  List<LogicalExpression> exprs = new ArrayList<LogicalExpression>();
	  List<String> cmps = new ArrayList();
	}
	@after{
	  $e = FunctionCallFactory.createByOp(exprs, cmps);
	}
  :  r1=relExpr { exprs.add($r1.e); 
    } ( cmpr= ( Equals | NEquals ) r2=relExpr {exprs.add($r2.e); cmps.add($cmpr.text); })*
  ;

relExpr returns [LogicalExpression e]
  :  left=addExpr {$e = $left.e; } (cmpr = (GTEquals | LTEquals | GT | LT) right=addExpr {$e = FunctionCallFactory.createExpression($cmpr.text, $left.e, $right.e); } )?
  ;

addExpr returns [LogicalExpression e]
	@init{
	  List<LogicalExpression> exprs = new ArrayList<LogicalExpression>();
	  List<String> ops = new ArrayList();
	}
	@after{
	  $e = FunctionCallFactory.createByOp(exprs, ops);
	}
  :  m1=mulExpr  {exprs.add($m1.e); } ( op=(Plus|Minus) m2=mulExpr {exprs.add($m2.e); ops.add($op.text); })* 
  ;

mulExpr returns [LogicalExpression e]
	@init{
	  List<LogicalExpression> exprs = new ArrayList<LogicalExpression>();
	  List<String> ops = new ArrayList();
	}
	@after{
	  $e = FunctionCallFactory.createByOp(exprs, ops);
	}
  :  p1=xorExpr  {exprs.add($p1.e);} (op=(Asterisk|ForwardSlash|Percent) p2=xorExpr {exprs.add($p2.e); ops.add($op.text); } )*
  ;

xorExpr returns [LogicalExpression e]
	@init{
	  List<LogicalExpression> exprs = new ArrayList<LogicalExpression>();
	  List<String> ops = new ArrayList();
	}
	@after{
	  $e = FunctionCallFactory.createByOp(exprs, ops);
	}
  :  u1=unaryExpr {exprs.add($u1.e);} (Caret u2=unaryExpr {exprs.add($u2.e); ops.add($Caret.text);} )*
  ;
  
unaryExpr returns [LogicalExpression e]
  :  sign=(Plus|Minus)? Number {$e = ValueExpressions.getNumericExpression($sign.text, $Number.text); }
  |  Minus atom {$e = FunctionCallFactory.createExpression("u-", $atom.e); }
  |  Excl atom {$e= FunctionCallFactory.createExpression("!", $atom.e); }
  |  atom {$e = $atom.e; }
  ;

atom returns [LogicalExpression e]
  :  Bool {$e = new ValueExpressions.BooleanExpression($Bool.text); }
  |  lookup {$e = $lookup.e; }
  ;

pathSegment returns [NameSegment seg]
  : s1=nameSegment {$seg = $s1.seg;}
  ;

nameSegment returns [NameSegment seg]
  : QuotedIdentifier ( (Period s1=pathSegment) | s2=arraySegment | s3=arraySegmentInputRef)? {$seg = new NameSegment($QuotedIdentifier.text, ($s1.seg == null ? $s2.seg  == null ? $s3.seg : $s2.seg : $s1.seg) ); }
  | Identifier ( (Period s1=pathSegment) | s2=arraySegment | s3=arraySegmentInputRef)? {$seg = new NameSegment($Identifier.text, ($s1.seg == null ? $s2.seg  == null ? $s3.seg : $s2.seg : $s1.seg) ); }
  ;
  
arraySegment returns [PathSegment seg]
  :  OBracket Number CBracket ( (Period s1=pathSegment) | s2=arraySegment | s3=arraySegmentInputRef)? {$seg = new ArraySegment($Number.text, ($s1.seg == null ? $s2.seg  == null ? $s3.seg : $s2.seg : $s1.seg) ); }
  ;

arraySegmentInputRef returns [PathSegment seg]
  :  OBracket Identifier CBracket ( (Period s1=pathSegment) | s2=arraySegment | s3=arraySegmentInputRef)? {$seg = new ArraySegmentInputRef($Identifier.text, ($s1.seg == null ? $s2.seg  == null ? $s3.seg : $s2.seg : $s1.seg) ); }
  ;

inputReference returns [InputReference e]
  :  InputReference OParen Number Comma pathSegment CParen { $e = new InputReference(Integer.parseInt($Number.text), new SchemaPath($pathSegment.seg )); }
  ;

lookup returns [LogicalExpression e]
  : inputReference {$e = $inputReference.e; }
  | listAggExpr {$e = $listAggExpr.e ;}
  | functionCall {$e = $functionCall.e ;}
  | convertCall {$e = $convertCall.e; }
  | castCall {$e = $castCall.e; }
  | pathSegment {$e = new SchemaPath($pathSegment.seg ); }
  | String {$e = new ValueExpressions.QuotedString($String.text ); }
  | OParen expression CParen  {$e = $expression.e; }
  | SingleQuote Identifier SingleQuote {$e = new SchemaPath($Identifier.text ); }
  | NULL {$e = NullExpression.INSTANCE;}
  ;
  
  
  
