/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.spark.sql.catalyst.analysis

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.expressions.Alias
import org.apache.spark.sql.catalyst.expressions.And
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.expressions.AttributeReference
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.expressions.IsNotNull
import org.apache.spark.sql.catalyst.expressions.Literal
import org.apache.spark.sql.catalyst.expressions.Literal.FalseLiteral
import org.apache.spark.sql.catalyst.expressions.Literal.TrueLiteral
import org.apache.spark.sql.catalyst.expressions.MonotonicallyIncreasingID
import org.apache.spark.sql.catalyst.expressions.PredicateHelper
import org.apache.spark.sql.catalyst.expressions.V2ExpressionUtils
import org.apache.spark.sql.catalyst.plans.FullOuter
import org.apache.spark.sql.catalyst.plans.Inner
import org.apache.spark.sql.catalyst.plans.LeftAnti
import org.apache.spark.sql.catalyst.plans.LeftOuter
import org.apache.spark.sql.catalyst.plans.RightOuter
import org.apache.spark.sql.catalyst.plans.logical.AppendData
import org.apache.spark.sql.catalyst.plans.logical.DeleteAction
import org.apache.spark.sql.catalyst.plans.logical.Filter
import org.apache.spark.sql.catalyst.plans.logical.HintInfo
import org.apache.spark.sql.catalyst.plans.logical.InsertAction
import org.apache.spark.sql.catalyst.plans.logical.Join
import org.apache.spark.sql.catalyst.plans.logical.JoinHint
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.plans.logical.MergeAction
import org.apache.spark.sql.catalyst.plans.logical.MergeIntoIcebergTable
import org.apache.spark.sql.catalyst.plans.logical.MergeRows
import org.apache.spark.sql.catalyst.plans.logical.NO_BROADCAST_HASH
import org.apache.spark.sql.catalyst.plans.logical.NoStatsUnaryNode
import org.apache.spark.sql.catalyst.plans.logical.Project
import org.apache.spark.sql.catalyst.plans.logical.ReplaceIcebergData
import org.apache.spark.sql.catalyst.plans.logical.UpdateAction
import org.apache.spark.sql.catalyst.plans.logical.WriteIcebergDelta
import org.apache.spark.sql.catalyst.util.RowDeltaUtils._
import org.apache.spark.sql.catalyst.util.WriteDeltaProjections
import org.apache.spark.sql.connector.catalog.SupportsRowLevelOperations
import org.apache.spark.sql.connector.expressions.FieldReference
import org.apache.spark.sql.connector.expressions.NamedReference
import org.apache.spark.sql.connector.write.RowLevelOperation.Command.MERGE
import org.apache.spark.sql.connector.write.RowLevelOperationTable
import org.apache.spark.sql.connector.write.SupportsDelta
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

/**
 * Assigns a rewrite plan for v2 tables that support rewriting data to handle MERGE statements.
 *
 * This rule assumes the commands have been fully resolved and all assignments have been aligned.
 * That's why it must be run after AlignRowLevelCommandAssignments.
 */
object RewriteMergeIntoTable extends RewriteRowLevelIcebergCommand with PredicateHelper {

  private final val ROW_FROM_SOURCE = "__row_from_source"
  private final val ROW_FROM_TARGET = "__row_from_target"
  private final val ROW_ID = "__row_id"

  private final val ROW_FROM_SOURCE_REF = FieldReference(ROW_FROM_SOURCE)
  private final val ROW_FROM_TARGET_REF = FieldReference(ROW_FROM_TARGET)

  override def apply(plan: LogicalPlan): LogicalPlan = plan resolveOperators {
    case m @ MergeIntoIcebergTable(aliasedTable, source, cond, matchedActions, notMatchedActions, None)
        if m.resolved && m.aligned && matchedActions.isEmpty && notMatchedActions.size == 1 =>

      EliminateSubqueryAliases(aliasedTable) match {
        case r: DataSourceV2Relation =>
          // NOT MATCHED conditions may only refer to columns in source so they can be pushed down
          val insertAction = notMatchedActions.head.asInstanceOf[InsertAction]
          val filteredSource = insertAction.condition match {
            case Some(insertCond) => Filter(insertCond, source)
            case None => source
          }

          // when there are no MATCHED actions, use a left anti join to remove any matching rows
          // and switch to using a regular append instead of a row-level merge
          // only unmatched source rows that match the condition are appended to the table
          val joinPlan = Join(filteredSource, r, LeftAnti, Some(cond), JoinHint.NONE)

          val outputExprs = insertAction.assignments.map(_.value)
          val outputColNames = r.output.map(_.name)
          val outputCols = outputExprs.zip(outputColNames).map { case (expr, name) =>
            Alias(expr, name)()
          }
          val project = Project(outputCols, joinPlan)

          AppendData.byPosition(r, project)

        case p =>
          throw new AnalysisException(s"$p is not an Iceberg table")
      }

    case m @ MergeIntoIcebergTable(aliasedTable, source, cond, matchedActions, notMatchedActions, None)
        if m.resolved && m.aligned && matchedActions.isEmpty =>

      EliminateSubqueryAliases(aliasedTable) match {
        case r: DataSourceV2Relation =>
          // when there are no MATCHED actions, use a left anti join to remove any matching rows
          // and switch to using a regular append instead of a row-level merge
          // only unmatched source rows that match action conditions are appended to the table
          val joinPlan = Join(source, r, LeftAnti, Some(cond), JoinHint.NONE)

          val notMatchedConditions = notMatchedActions.map(actionCondition)
          val notMatchedOutputs = notMatchedActions.map(notMatchedActionOutput(_, Nil))

          // merge rows as there are multiple not matched actions
          val mergeRows = MergeRows(
            isSourceRowPresent = TrueLiteral,
            isTargetRowPresent = FalseLiteral,
            matchedConditions = Nil,
            matchedOutputs = Nil,
            notMatchedConditions = notMatchedConditions,
            notMatchedOutputs = notMatchedOutputs,
            targetOutput = Nil,
            performCardinalityCheck = false,
            emitNotMatchedTargetRows = false,
            output = buildMergeRowsOutput(Nil, notMatchedOutputs, r.output),
            joinPlan)

          AppendData.byPosition(r, mergeRows)

        case p =>
          throw new AnalysisException(s"$p is not an Iceberg table")
      }

    case m @ MergeIntoIcebergTable(aliasedTable, source, cond, matchedActions, notMatchedActions, None)
        if m.resolved && m.aligned =>

      EliminateSubqueryAliases(aliasedTable) match {
        case r @ DataSourceV2Relation(tbl: SupportsRowLevelOperations, _, _, _, _) =>
          val table = buildOperationTable(tbl, MERGE, CaseInsensitiveStringMap.empty())
          val rewritePlan = table.operation match {
            case _: SupportsDelta =>
              buildWriteDeltaPlan(r, table, source, cond, matchedActions, notMatchedActions)
            case _ =>
              buildReplaceDataPlan(r, table, source, cond, matchedActions, notMatchedActions)
          }

          m.copy(rewritePlan = Some(rewritePlan))

        case p =>
          throw new AnalysisException(s"$p is not an Iceberg table")
      }
  }

  // build a rewrite plan for sources that support replacing groups of data (e.g. files, partitions)
  private def buildReplaceDataPlan(
      relation: DataSourceV2Relation,
      operationTable: RowLevelOperationTable,
      source: LogicalPlan,
      cond: Expression,
      matchedActions: Seq[MergeAction],
      notMatchedActions: Seq[MergeAction]): ReplaceIcebergData = {

    // resolve all needed attrs (e.g. metadata attrs for grouping data on write)
    val metadataAttrs = resolveRequiredMetadataAttrs(relation, operationTable.operation)

    // construct a scan relation and include all required metadata columns
    val readRelation = buildRelationWithAttrs(relation, operationTable, metadataAttrs)
    val readAttrs = readRelation.output

    val performCardinalityCheck = isCardinalityCheckNeeded(matchedActions)

    // project an extra column to check if a target row exists after the join
    // if needed, project a synthetic row ID to perform the cardinality check
    val rowFromTarget = Alias(TrueLiteral, ROW_FROM_TARGET)()
    val targetTableProjExprs = if (performCardinalityCheck) {
      val rowId = Alias(MonotonicallyIncreasingID(), ROW_ID)()
      readAttrs ++ Seq(rowFromTarget, rowId)
    } else {
      readAttrs :+ rowFromTarget
    }
    val targetTableProj = Project(targetTableProjExprs, readRelation)

    // project an extra column to check if a source row exists after the join
    val rowFromSource = Alias(TrueLiteral, ROW_FROM_SOURCE)()
    val sourceTableProjExprs = source.output :+ rowFromSource
    val sourceTableProj = Project(sourceTableProjExprs, source)

    // use left outer join if there is no NOT MATCHED action, unmatched source rows can be discarded
    // use full outer join in all other cases, unmatched source rows may be needed
    // disable broadcasts for the target table to perform the cardinality check
    val joinType = if (notMatchedActions.isEmpty) LeftOuter else FullOuter
    val joinHint = JoinHint(leftHint = Some(HintInfo(Some(NO_BROADCAST_HASH))), rightHint = None)
    val joinPlan = Join(NoStatsUnaryNode(targetTableProj), sourceTableProj, joinType, Some(cond), joinHint)

    val matchedConditions = matchedActions.map(actionCondition)
    val matchedOutputs = matchedActions.map(matchedActionOutput(_, metadataAttrs))

    val notMatchedConditions = notMatchedActions.map(actionCondition)
    val notMatchedOutputs = notMatchedActions.map(notMatchedActionOutput(_, metadataAttrs))

    val rowFromSourceAttr = resolveAttrRef(ROW_FROM_SOURCE_REF, joinPlan)
    val rowFromTargetAttr = resolveAttrRef(ROW_FROM_TARGET_REF, joinPlan)

    val mergeRows = MergeRows(
      isSourceRowPresent = IsNotNull(rowFromSourceAttr),
      isTargetRowPresent = if (notMatchedActions.isEmpty) TrueLiteral else IsNotNull(rowFromTargetAttr),
      matchedConditions = matchedConditions,
      matchedOutputs = matchedOutputs,
      notMatchedConditions = notMatchedConditions,
      notMatchedOutputs = notMatchedOutputs,
      targetOutput = readAttrs,
      performCardinalityCheck = performCardinalityCheck,
      emitNotMatchedTargetRows = true,
      output = buildMergeRowsOutput(matchedOutputs, notMatchedOutputs :+ readAttrs, readAttrs),
      joinPlan)

    // build a plan to replace read groups in the table
    val writeRelation = relation.copy(table = operationTable)
    ReplaceIcebergData(writeRelation, mergeRows, relation)
  }

  // build a rewrite plan for sources that support row deltas
  private def buildWriteDeltaPlan(
      relation: DataSourceV2Relation,
      operationTable: RowLevelOperationTable,
      source: LogicalPlan,
      cond: Expression,
      matchedActions: Seq[MergeAction],
      notMatchedActions: Seq[MergeAction]): WriteIcebergDelta = {

    // resolve all needed attrs (e.g. row ID and any required metadata attrs)
    val rowAttrs = relation.output
    val rowIdAttrs = resolveRowIdAttrs(relation, operationTable.operation)
    val metadataAttrs = resolveRequiredMetadataAttrs(relation, operationTable.operation)

    // construct a scan relation and include all required metadata columns
    val readRelation = buildRelationWithAttrs(relation, operationTable, rowIdAttrs ++ metadataAttrs)
    val readAttrs = readRelation.output

    val (targetCond, joinCond) = splitMergeCond(cond, readRelation)

    val performCardinalityCheck = isCardinalityCheckNeeded(matchedActions)

    // project an extra column to check if a target row exists after the join
    // if needed, project a synthetic row ID to perform the cardinality check
    val rowFromTarget = Alias(TrueLiteral, ROW_FROM_TARGET)()
    val targetTableProjExprs = if (performCardinalityCheck) {
      val rowId = Alias(MonotonicallyIncreasingID(), ROW_ID)()
      readAttrs ++ Seq(rowFromTarget, rowId)
    } else {
      readAttrs :+ rowFromTarget
    }
    val targetTableProj = Project(targetTableProjExprs, Filter(targetCond, readRelation))

    // project an extra column to check if a source row exists after the join
    val sourceTableProjExprs = source.output :+ Alias(TrueLiteral, ROW_FROM_SOURCE)()
    val sourceTableProj = Project(sourceTableProjExprs, source)

    // use inner join if there is no NOT MATCHED action, unmatched source rows can be discarded
    // use right outer join in all other cases, unmatched source rows may be needed
    // also disable broadcasts for the target table to perform the cardinality check
    val joinType = if (notMatchedActions.isEmpty) Inner else RightOuter
    val joinHint = JoinHint(leftHint = Some(HintInfo(Some(NO_BROADCAST_HASH))), rightHint = None)
    val joinPlan = Join(NoStatsUnaryNode(targetTableProj), sourceTableProj, joinType, Some(joinCond), joinHint)

    val metadataReadAttrs = readAttrs.filterNot(relation.outputSet.contains)

    val matchedConditions = matchedActions.map(actionCondition)
    val matchedOutputs = matchedActions.map { action =>
      matchedDeltaActionOutput(action, rowAttrs, rowIdAttrs, metadataReadAttrs)
    }

    val notMatchedConditions = notMatchedActions.map(actionCondition)
    val notMatchedOutputs = notMatchedActions.map { action =>
      notMatchedDeltaActionOutput(action, metadataReadAttrs)
    }

    val operationTypeAttr = AttributeReference(OPERATION_COLUMN, IntegerType, nullable = false)()
    val rowFromSourceAttr = resolveAttrRef(ROW_FROM_SOURCE_REF, joinPlan)
    val rowFromTargetAttr = resolveAttrRef(ROW_FROM_TARGET_REF, joinPlan)

    // merged rows must contain values for the operation type and all read attrs
    val mergeRowsOutput = buildMergeRowsOutput(matchedOutputs, notMatchedOutputs, operationTypeAttr +: readAttrs)

    val mergeRows = MergeRows(
      isSourceRowPresent = IsNotNull(rowFromSourceAttr),
      isTargetRowPresent = if (notMatchedActions.isEmpty) TrueLiteral else IsNotNull(rowFromTargetAttr),
      matchedConditions = matchedConditions,
      matchedOutputs = matchedOutputs,
      notMatchedConditions = notMatchedConditions,
      notMatchedOutputs = notMatchedOutputs,
      // only needed if emitting unmatched target rows
      targetOutput = Nil,
      performCardinalityCheck = performCardinalityCheck,
      emitNotMatchedTargetRows = false,
      output = mergeRowsOutput,
      joinPlan)

    // build a plan to write the row delta to the table
    val writeRelation = relation.copy(table = operationTable)
    val projections = buildDeltaProjections(mergeRows, rowAttrs, rowIdAttrs, metadataAttrs)
    WriteIcebergDelta(writeRelation, mergeRows, relation, projections)
  }

  private def actionCondition(action: MergeAction): Expression = {
    action.condition.getOrElse(TrueLiteral)
  }

  private def matchedActionOutput(
      clause: MergeAction,
      metadataAttrs: Seq[Attribute]): Seq[Seq[Expression]] = {

    clause match {
      case u: UpdateAction =>
        Seq(u.assignments.map(_.value) ++ metadataAttrs)

      case _: DeleteAction =>
        Nil

      case other =>
        throw new AnalysisException(s"Unexpected WHEN MATCHED action: $other")
    }
  }

  private def notMatchedActionOutput(
      clause: MergeAction,
      metadataAttrs: Seq[Attribute]): Seq[Expression] = {

    clause match {
      case i: InsertAction =>
        i.assignments.map(_.value) ++ metadataAttrs.map(attr => Literal(null, attr.dataType))

      case other =>
        throw new AnalysisException(s"Unexpected WHEN NOT MATCHED action: $other")
    }
  }

  private def matchedDeltaActionOutput(
      action: MergeAction,
      rowAttrs: Seq[Attribute],
      rowIdAttrs: Seq[Attribute],
      metadataAttrs: Seq[Attribute]): Seq[Seq[Expression]] = {

    action match {
      case u: UpdateAction =>
        val delete = deltaDeleteOutput(rowAttrs, rowIdAttrs, metadataAttrs)
        val insert = deltaInsertOutput(u.assignments.map(_.value), metadataAttrs)
        Seq(delete, insert)

      case _: DeleteAction =>
        val delete = deltaDeleteOutput(rowAttrs, rowIdAttrs, metadataAttrs)
        Seq(delete)

      case other =>
        throw new AnalysisException(s"Unexpected WHEN MATCHED action: $other")
    }
  }

  private def notMatchedDeltaActionOutput(
      action: MergeAction,
      metadataAttrs: Seq[Attribute]): Seq[Expression] = {

    action match {
      case i: InsertAction =>
        deltaInsertOutput(i.assignments.map(_.value), metadataAttrs)

      case other =>
        throw new AnalysisException(s"Unexpected WHEN NOT MATCHED action: $other")
    }
  }

  private def buildMergeRowsOutput(
      matchedOutputs: Seq[Seq[Seq[Expression]]],
      notMatchedOutputs: Seq[Seq[Expression]],
      attrs: Seq[Attribute]): Seq[Attribute] = {

    // collect all outputs from matched and not matched actions (ignoring actions that discard rows)
    val outputs = matchedOutputs.flatten.filter(_.nonEmpty) ++ notMatchedOutputs.filter(_.nonEmpty)
    buildMergingOutput(outputs, attrs)
  }

  private def isCardinalityCheckNeeded(actions: Seq[MergeAction]): Boolean = actions match {
    case Seq(DeleteAction(None)) => false
    case _ => true
  }

  private def resolveAttrRef(ref: NamedReference, plan: LogicalPlan): AttributeReference = {
    V2ExpressionUtils.resolveRef[AttributeReference](ref, plan)
  }

  private def buildDeltaProjections(
      mergeRows: MergeRows,
      rowAttrs: Seq[Attribute],
      rowIdAttrs: Seq[Attribute],
      metadataAttrs: Seq[Attribute]): WriteDeltaProjections = {

    val outputs = mergeRows.matchedOutputs.flatten ++ mergeRows.notMatchedOutputs
    buildDeltaProjections(mergeRows, outputs, rowAttrs, rowIdAttrs, metadataAttrs)
  }

  // splits the MERGE condition into a predicate that references columns only from the target table,
  // which can be pushed down, and a predicate used as a join condition to find matches
  private def splitMergeCond(
      cond: Expression,
      targetTable: LogicalPlan): (Expression, Expression) = {

    val (targetPredicates, joinPredicates) = splitConjunctivePredicates(cond)
      .partition(_.references.subsetOf(targetTable.outputSet))
    val targetCond = targetPredicates.reduceOption(And).getOrElse(TrueLiteral)
    val joinCond = joinPredicates.reduceOption(And).getOrElse(TrueLiteral)
    (targetCond, joinCond)
  }
}
