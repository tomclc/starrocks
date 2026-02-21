// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.sql.optimizer.rule.transformation;

import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.OlapTable;
import com.starrocks.common.Config;
import com.starrocks.common.SpatialSearchOptions;
import com.starrocks.sql.ast.IndexDef;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalJoinOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Detects spatial join patterns like:
 *   ST_Contains(ST_Polygon(r.region_wkt), ST_Point(l.lng, l.lat))
 * where one side references the build table (regions) and the other references
 * the probe table (locations with spatial index).
 *
 * Marks the probe-side scan for runtime spatial filter so the spatial index
 * can be used to narrow candidates before evaluating ST_Contains.
 */
public class SpatialJoinRuntimeFilterRule extends TransformationRule {

    public SpatialJoinRuntimeFilterRule() {
        super(RuleType.TF_SPATIAL_JOIN_RUNTIME_FILTER,
                Pattern.create(OperatorType.LOGICAL_JOIN, OperatorType.PATTERN_LEAF, OperatorType.PATTERN_LEAF));
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        if (!Config.enable_spatial_index) {
            return false;
        }
        LogicalJoinOperator join = (LogicalJoinOperator) input.getOp();
        if (join.getOnPredicate() == null) {
            return false;
        }
        // Only handle INNER and CROSS joins for spatial predicates
        if (!join.isInnerOrCrossJoin()) {
            return false;
        }
        // Check if any child is a scan with a spatial index
        return hasSpatialIndexedScan(input.inputAt(0)) || hasSpatialIndexedScan(input.inputAt(1));
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalJoinOperator join = (LogicalJoinOperator) input.getOp();
        ScalarOperator onPredicate = join.getOnPredicate();
        if (onPredicate == null) {
            return Collections.emptyList();
        }

        // Also check WHERE predicates (pushed to join's predicate)
        ScalarOperator combinedPredicate = onPredicate;
        if (join.getPredicate() != null) {
            combinedPredicate = Utils.compoundAnd(onPredicate, join.getPredicate());
        }

        List<ScalarOperator> conjuncts = Utils.extractConjuncts(combinedPredicate);
        for (ScalarOperator conjunct : conjuncts) {
            if (tryMarkSpatialJoin(conjunct, input)) {
                return List.of(input);
            }
        }

        return Collections.emptyList();
    }

    /**
     * Try to detect ST_Contains(build_expr, ST_Point(probe_lng, probe_lat)) pattern
     * and mark the probe-side scan for runtime spatial filter.
     */
    private boolean tryMarkSpatialJoin(ScalarOperator predicate, OptExpression joinExpr) {
        if (!(predicate instanceof CallOperator)) {
            return false;
        }
        CallOperator call = (CallOperator) predicate;
        if (!FunctionSet.ST_CONTAINS.equalsIgnoreCase(call.getFnName()) || call.getChildren().size() != 2) {
            return false;
        }

        ScalarOperator polygonExpr = call.getChild(0);
        ScalarOperator pointExpr = call.getChild(1);

        // pointExpr should be ST_Point(lng, lat) referencing probe-side columns
        if (!(pointExpr instanceof CallOperator)) {
            return false;
        }
        CallOperator pointCall = (CallOperator) pointExpr;
        if (!FunctionSet.ST_POINT.equalsIgnoreCase(pointCall.getFnName()) || pointCall.getChildren().size() != 2) {
            return false;
        }

        // The point args should reference columns (probe-side)
        Set<ColumnRefOperator> pointCols = pointExpr.getColumnRefs();
        if (pointCols.isEmpty()) {
            return false;
        }

        // The polygon expr should reference columns from the build side (not constants—those
        // are already handled by the scan-level RewriteToSpatialPlanRule)
        Set<ColumnRefOperator> polygonCols = polygonExpr.getColumnRefs();
        if (polygonCols.isEmpty()) {
            // If polygon is a constant, the scan-level rule already handles it
            return false;
        }

        // Determine which child is the probe side (has spatial index on the point columns)
        OptExpression leftChild = joinExpr.inputAt(0);
        OptExpression rightChild = joinExpr.inputAt(1);

        LogicalOlapScanOperator probeScan = findSpatialIndexedScan(leftChild, pointCols);
        if (probeScan == null) {
            probeScan = findSpatialIndexedScan(rightChild, pointCols);
        }
        if (probeScan == null) {
            return false;
        }

        // Mark the probe-side scan for runtime spatial filter
        SpatialSearchOptions opts = new SpatialSearchOptions();
        opts.setEnableSpatialIndex(true);
        opts.setPredicateType("contains");
        opts.setRuntimeSpatialFilter(true);
        // runtime_wkt_regions will be populated at execution time from the build side
        probeScan.setSpatialSearchOptions(opts);

        return true;
    }

    private boolean hasSpatialIndexedScan(OptExpression expr) {
        if (expr.getOp() instanceof LogicalOlapScanOperator) {
            OlapTable table = (OlapTable) ((LogicalOlapScanOperator) expr.getOp()).getTable();
            return table.getIndexes().stream()
                    .anyMatch(idx -> idx.getIndexType() == IndexDef.IndexType.SPATIAL);
        }
        return false;
    }

    /**
     * Find a LogicalOlapScanOperator in the expression tree that has a spatial index
     * and whose columns include the given column refs.
     */
    private LogicalOlapScanOperator findSpatialIndexedScan(OptExpression expr, Set<ColumnRefOperator> cols) {
        if (expr.getOp() instanceof LogicalOlapScanOperator) {
            LogicalOlapScanOperator scan = (LogicalOlapScanOperator) expr.getOp();
            OlapTable table = (OlapTable) scan.getTable();
            boolean hasSpatialIndex = table.getIndexes().stream()
                    .anyMatch(idx -> idx.getIndexType() == IndexDef.IndexType.SPATIAL);
            if (hasSpatialIndex) {
                // Check if the scan's columns include the referenced columns
                Set<ColumnRefOperator> scanCols = scan.getColumnMetaToColRefMap().values()
                        .stream().collect(java.util.stream.Collectors.toSet());
                if (scanCols.containsAll(cols)) {
                    return scan;
                }
            }
        }
        // Recurse into children
        for (int i = 0; i < expr.arity(); i++) {
            LogicalOlapScanOperator result = findSpatialIndexedScan(expr.inputAt(i), cols);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
