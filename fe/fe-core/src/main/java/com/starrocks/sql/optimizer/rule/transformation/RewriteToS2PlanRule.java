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
import com.starrocks.common.S2SearchOptions;
import com.starrocks.sql.ast.IndexDef;
import com.starrocks.sql.ast.expression.BinaryType;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
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
 * This rule detects spatial function calls in the scan operator's predicate (after predicate
 * push-down), checks if the referenced columns have an S2 index, and if so, extracts spatial
 * parameters and sets S2SearchOptions on the scan operator to enable index-based pruning at
 * execution time on the BE.
 *
 * The original predicate is kept as a residual filter since S2 cell indexing is approximate —
 * it prunes non-matching rows but may include false positives that the exact spatial function
 * evaluation will filter out.
 *
 * Supported patterns:
 * - ST_Distance_Sphere(col_lng, col_lat, const_lng, const_lat) < threshold
 * - ST_Distance_Sphere(const_lng, const_lat, col_lng, col_lat) < threshold
 *
 * Pattern: LogicalOlapScan (with pushed-down predicate)
 */
public class RewriteToS2PlanRule extends TransformationRule {

    public RewriteToS2PlanRule() {
        super(RuleType.TF_S2_REWRITE_RULE,
                Pattern.create(OperatorType.LOGICAL_OLAP_SCAN));
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        if (!Config.enable_experimental_s2) {
            return false;
        }

        LogicalOlapScanOperator scanOp = (LogicalOlapScanOperator) input.getOp();
        if (!(scanOp.getTable() instanceof OlapTable)) {
            return false;
        }

        // Already rewritten — don't apply again
        if (scanOp.getS2SearchOptions() != null) {
            return false;
        }

        // Must have a predicate to extract spatial params from
        if (scanOp.getPredicate() == null) {
            return false;
        }

        // Check if the table has any S2 index
        OlapTable table = (OlapTable) scanOp.getTable();
        return table.getIndexes().stream()
                .anyMatch(i -> i.getIndexType() == IndexDef.IndexType.S2);
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalOlapScanOperator scanOp = (LogicalOlapScanOperator) input.getOp();
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

        // Check the scan operator's pushed-down predicate for spatial functions
        ScalarOperator predicate = scanOp.getPredicate();
        List<ScalarOperator> conjuncts = Utils.extractConjuncts(predicate);

        S2SearchOptions options = null;
        for (ScalarOperator conjunct : conjuncts) {
            options = extractSpatialParams(conjunct, scanOp, s2IndexedColumnIds, s2Indexes);
            if (options != null) {
                break;
            }
        }

        if (options == null) {
            return List.of();
        }

        // Set S2 search options on the scan operator, keeping the original predicate
        LogicalOlapScanOperator newScanOp = LogicalOlapScanOperator.builder()
                .withOperator(scanOp)
                .setS2SearchOptions(options)
                .build();

        return List.of(OptExpression.create(newScanOp));
    }

    /**
     * Try to extract spatial parameters from a predicate.
     *
     * Handles: ST_Distance_Sphere(lng1, lat1, lng2, lat2) < threshold
     * where two args are column refs (indexed) and two are constants.
     *
     * Returns S2SearchOptions with query_type="circle", center point, and radius,
     * or null if the pattern doesn't match.
     */
    private S2SearchOptions extractSpatialParams(ScalarOperator conjunct,
                                                  LogicalOlapScanOperator scanOp,
                                                  Set<ColumnId> s2IndexedColumnIds,
                                                  List<Index> s2Indexes) {
        // Pattern: st_distance_sphere(...) < threshold
        if (conjunct instanceof BinaryPredicateOperator) {
            BinaryPredicateOperator binOp = (BinaryPredicateOperator) conjunct;
            BinaryType binType = binOp.getBinaryType();

            CallOperator callOp = null;
            ConstantOperator thresholdOp = null;

            // st_distance_sphere(...) < threshold  or  threshold > st_distance_sphere(...)
            if ((binType == BinaryType.LT || binType == BinaryType.LE) &&
                    binOp.getChild(0) instanceof CallOperator &&
                    binOp.getChild(1) instanceof ConstantOperator) {
                callOp = (CallOperator) binOp.getChild(0);
                thresholdOp = (ConstantOperator) binOp.getChild(1);
            } else if ((binType == BinaryType.GT || binType == BinaryType.GE) &&
                    binOp.getChild(1) instanceof CallOperator &&
                    binOp.getChild(0) instanceof ConstantOperator) {
                callOp = (CallOperator) binOp.getChild(1);
                thresholdOp = (ConstantOperator) binOp.getChild(0);
            }

            if (callOp != null && thresholdOp != null) {
                return extractDistanceSphereParams(callOp, thresholdOp, scanOp,
                        s2IndexedColumnIds, s2Indexes);
            }
        }

        // Pattern: direct spatial function call (e.g., ST_Contains)
        if (conjunct instanceof CallOperator) {
            CallOperator callOp = (CallOperator) conjunct;
            if (isSpatialFunctionOnIndexedColumns(callOp, scanOp, s2IndexedColumnIds)) {
                // For ST_Contains without extractable params, just enable the index with a broad covering
                return buildDefaultOptions(s2Indexes);
            }
        }

        return null;
    }

    /**
     * Extract parameters from ST_Distance_Sphere(lng1, lat1, lng2, lat2) < threshold
     */
    private S2SearchOptions extractDistanceSphereParams(CallOperator callOp,
                                                         ConstantOperator threshold,
                                                         LogicalOlapScanOperator scanOp,
                                                         Set<ColumnId> s2IndexedColumnIds,
                                                         List<Index> s2Indexes) {
        String fnName = callOp.getFnName();
        if (!ST_DISTANCE_SPHERE.equalsIgnoreCase(fnName)) {
            return null;
        }

        if (callOp.getChildren().size() != 4) {
            return null;
        }

        Map<ColumnRefOperator, Column> colRefMap = scanOp.getColRefToColumnMetaMap();

        // Identify which two args are constants (the query point) and which are column refs (the data)
        ScalarOperator arg0 = callOp.getChild(0); // lng1
        ScalarOperator arg1 = callOp.getChild(1); // lat1
        ScalarOperator arg2 = callOp.getChild(2); // lng2
        ScalarOperator arg3 = callOp.getChild(3); // lat2

        double constLng;
        double constLat;
        boolean hasIndexedCols;

        // Check if args 0,1 are indexed columns and args 2,3 are constants
        boolean pair1IsCol = referencesIndexedColumn(arg0, colRefMap, s2IndexedColumnIds) &&
                referencesIndexedColumn(arg1, colRefMap, s2IndexedColumnIds);
        boolean pair2IsConst = (arg2 instanceof ConstantOperator) && (arg3 instanceof ConstantOperator);

        boolean pair2IsCol = referencesIndexedColumn(arg2, colRefMap, s2IndexedColumnIds) &&
                referencesIndexedColumn(arg3, colRefMap, s2IndexedColumnIds);
        boolean pair1IsConst = (arg0 instanceof ConstantOperator) && (arg1 instanceof ConstantOperator);

        if (pair1IsCol && pair2IsConst) {
            constLng = ((ConstantOperator) arg2).getDouble();
            constLat = ((ConstantOperator) arg3).getDouble();
            hasIndexedCols = true;
        } else if (pair2IsCol && pair1IsConst) {
            constLng = ((ConstantOperator) arg0).getDouble();
            constLat = ((ConstantOperator) arg1).getDouble();
            hasIndexedCols = true;
        } else {
            return null;
        }

        if (!hasIndexedCols) {
            return null;
        }

        double radiusMeters = threshold.getDouble();
        S2SearchOptions options = buildDefaultOptions(s2Indexes);
        options.setQueryType("circle");
        options.setQueryLat(constLat);
        options.setQueryLng(constLng);
        options.setQueryRadiusMeters(radiusMeters);
        return options;
    }

    /**
     * Build default S2SearchOptions with cell_level and max_cells from the first S2 index.
     */
    private S2SearchOptions buildDefaultOptions(List<Index> s2Indexes) {
        S2SearchOptions options = new S2SearchOptions();
        options.setEnableS2Index(true);
        if (!s2Indexes.isEmpty()) {
            Index idx = s2Indexes.get(0);
            Map<String, String> props = idx.getProperties();
            if (props != null) {
                String cellLevelStr = props.get("cell_level");
                if (cellLevelStr != null) {
                    try {
                        options.setCellLevel(Integer.parseInt(cellLevelStr));
                    } catch (NumberFormatException ignored) {
                    }
                }
                String maxCellsStr = props.get("max_cells");
                if (maxCellsStr != null) {
                    try {
                        options.setMaxCells(Integer.parseInt(maxCellsStr));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return options;
    }

    /**
     * Checks if a scalar operator is a spatial function call (ST_CONTAINS or ST_DISTANCE_SPHERE)
     * that references columns with an S2 index.
     */
    private boolean isSpatialFunctionOnIndexedColumns(CallOperator callOp,
                                                       LogicalOlapScanOperator scanOp,
                                                       Set<ColumnId> s2IndexedColumnIds) {
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
