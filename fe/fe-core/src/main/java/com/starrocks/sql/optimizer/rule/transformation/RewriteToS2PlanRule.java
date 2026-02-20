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

import com.starrocks.catalog.Column;
import com.starrocks.catalog.ColumnId;
import com.starrocks.catalog.Index;
import com.starrocks.catalog.OlapTable;
import com.starrocks.common.Config;
import com.starrocks.sql.ast.IndexDef;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalFilterOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.starrocks.catalog.FunctionSet.ST_CONTAINS;
import static com.starrocks.catalog.FunctionSet.ST_DISTANCE_SPHERE;

/**
 * Rewrites spatial predicates (ST_CONTAINS, ST_DISTANCE_SPHERE) to leverage S2 cell indexes.
 *
 * This rule detects spatial function calls in filter predicates, checks if the referenced columns
 * have an S2 index, and if so, sets s2SearchOptions on the scan operator to enable index-based pruning.
 *
 * The original predicate is kept as a residual filter since S2 cell indexing is approximate —
 * it prunes non-matching rows but may include false positives that the exact spatial function
 * evaluation will filter out.
 *
 * Pattern: LogicalFilter -> LogicalOlapScan
 */
public class RewriteToS2PlanRule extends TransformationRule {

    public RewriteToS2PlanRule() {
        super(RuleType.TF_S2_REWRITE_RULE,
                Pattern.create(OperatorType.LOGICAL_FILTER)
                        .addChildren(Pattern.create(OperatorType.LOGICAL_OLAP_SCAN)));
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        if (!Config.enable_experimental_s2) {
            return false;
        }

        LogicalOlapScanOperator scanOp = (LogicalOlapScanOperator) input.getInputs().get(0).getOp();
        if (!(scanOp.getTable() instanceof OlapTable)) {
            return false;
        }

        // Check if the table has any S2 index
        OlapTable table = (OlapTable) scanOp.getTable();
        return table.getIndexes().stream()
                .anyMatch(i -> i.getIndexType() == IndexDef.IndexType.S2);
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalFilterOperator filterOp = (LogicalFilterOperator) input.getOp();
        LogicalOlapScanOperator scanOp = (LogicalOlapScanOperator) input.getInputs().get(0).getOp();
        OlapTable table = (OlapTable) scanOp.getTable();

        // Find S2 indexes and their indexed column IDs
        List<Index> s2Indexes = table.getIndexes().stream()
                .filter(i -> i.getIndexType() == IndexDef.IndexType.S2)
                .collect(Collectors.toList());

        if (s2Indexes.isEmpty()) {
            return List.of();
        }

        // Get all indexed column IDs
        Set<ColumnId> s2IndexedColumnIds = s2Indexes.stream()
                .flatMap(idx -> idx.getColumns().stream())
                .collect(Collectors.toSet());

        // Check if the filter predicate contains spatial functions referencing S2-indexed columns
        ScalarOperator predicate = filterOp.getPredicate();
        List<ScalarOperator> conjuncts = Utils.extractConjuncts(predicate);

        boolean hasSpatialPredicate = false;
        for (ScalarOperator conjunct : conjuncts) {
            if (isSpatialFunctionOnIndexedColumns(conjunct, scanOp, s2IndexedColumnIds)) {
                hasSpatialPredicate = true;
                break;
            }
        }

        if (!hasSpatialPredicate) {
            return List.of();
        }

        // Set the S2 search flag on the scan operator.
        // The actual cell range computation happens at execution time in the BE,
        // using the constant region parameters from the predicate.
        // The predicate is kept as a residual filter for exact evaluation.
        LogicalOlapScanOperator newScanOp = LogicalOlapScanOperator.builder()
                .withOperator(scanOp)
                .setUseS2Index(true)
                .build();

        // Keep the original filter — S2 index only prunes, doesn't replace the exact check
        return List.of(OptExpression.create(filterOp, OptExpression.create(newScanOp)));
    }

    /**
     * Checks if a scalar operator is a spatial function call (ST_CONTAINS or ST_DISTANCE_SPHERE)
     * that references columns with an S2 index.
     */
    private boolean isSpatialFunctionOnIndexedColumns(ScalarOperator operator,
                                                       LogicalOlapScanOperator scanOp,
                                                       Set<ColumnId> s2IndexedColumnIds) {
        if (!(operator instanceof CallOperator)) {
            return false;
        }

        CallOperator callOp = (CallOperator) operator;
        String fnName = callOp.getFnName();

        if (!ST_CONTAINS.equalsIgnoreCase(fnName) && !ST_DISTANCE_SPHERE.equalsIgnoreCase(fnName)) {
            return false;
        }

        // Check if any child references an S2-indexed column
        Map<ColumnRefOperator, Column> colRefMap = scanOp.getColRefToColumnMetaMap();
        for (ScalarOperator child : callOp.getChildren()) {
            if (referencesIndexedColumn(child, colRefMap, s2IndexedColumnIds)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Recursively checks if an operator references a column that is part of an S2 index.
     */
    private boolean referencesIndexedColumn(ScalarOperator operator,
                                             Map<ColumnRefOperator, Column> colRefMap,
                                             Set<ColumnId> s2IndexedColumnIds) {
        if (operator instanceof ColumnRefOperator) {
            Column col = colRefMap.get(operator);
            return col != null && s2IndexedColumnIds.contains(col.getColumnId());
        }

        for (ScalarOperator child : operator.getChildren()) {
            if (referencesIndexedColumn(child, colRefMap, s2IndexedColumnIds)) {
                return true;
            }
        }

        return false;
    }
}
