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
import com.starrocks.common.S2SearchOptions;
import com.starrocks.sql.ast.expression.BinaryType;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.starrocks.catalog.FunctionSet.ST_CONTAINS;
import static com.starrocks.catalog.FunctionSet.ST_DISTANCE_SPHERE;

/**
 * Shared utility for extracting S2 spatial parameters from predicates.
 * Used by both RewriteToS2PlanRule (OLAP) and RewriteToS2PlanRuleForIceberg.
 */
public class S2SpatialParamExtractor {

    private S2SpatialParamExtractor() {
    }

    /**
     * Try to extract S2SearchOptions from a list of conjuncts.
     * Returns the first successfully extracted options, or null if none match.
     */
    public static S2SearchOptions extractFromConjuncts(ScalarOperator predicate,
                                                        Map<ColumnRefOperator, Column> colRefMap,
                                                        Set<ColumnId> s2IndexedColumnIds,
                                                        List<Index> s2Indexes) {
        List<ScalarOperator> conjuncts = Utils.extractConjuncts(predicate);
        for (ScalarOperator conjunct : conjuncts) {
            S2SearchOptions options = extractSpatialParams(conjunct, colRefMap, s2IndexedColumnIds, s2Indexes);
            if (options != null) {
                return options;
            }
        }
        return null;
    }

    /**
     * Try to extract spatial parameters from a single predicate.
     */
    public static S2SearchOptions extractSpatialParams(ScalarOperator conjunct,
                                                        Map<ColumnRefOperator, Column> colRefMap,
                                                        Set<ColumnId> s2IndexedColumnIds,
                                                        List<Index> s2Indexes) {
        // Pattern: st_distance_sphere(...) < threshold
        if (conjunct instanceof BinaryPredicateOperator) {
            BinaryPredicateOperator binOp = (BinaryPredicateOperator) conjunct;
            BinaryType binType = binOp.getBinaryType();

            CallOperator callOp = null;
            ConstantOperator thresholdOp = null;

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
                return extractDistanceSphereParams(callOp, thresholdOp, colRefMap,
                        s2IndexedColumnIds, s2Indexes);
            }
        }

        // Pattern: direct spatial function call (e.g., ST_Contains)
        if (conjunct instanceof CallOperator) {
            CallOperator callOp = (CallOperator) conjunct;
            if (isSpatialFunctionOnIndexedColumns(callOp, colRefMap, s2IndexedColumnIds)) {
                return buildDefaultOptions(s2Indexes);
            }
        }

        return null;
    }

    /**
     * Extract parameters from ST_Distance_Sphere(lng1, lat1, lng2, lat2) < threshold
     */
    public static S2SearchOptions extractDistanceSphereParams(CallOperator callOp,
                                                               ConstantOperator threshold,
                                                               Map<ColumnRefOperator, Column> colRefMap,
                                                               Set<ColumnId> s2IndexedColumnIds,
                                                               List<Index> s2Indexes) {
        String fnName = callOp.getFnName();
        if (!ST_DISTANCE_SPHERE.equalsIgnoreCase(fnName)) {
            return null;
        }

        if (callOp.getChildren().size() != 4) {
            return null;
        }

        ScalarOperator arg0 = callOp.getChild(0); // lng1
        ScalarOperator arg1 = callOp.getChild(1); // lat1
        ScalarOperator arg2 = callOp.getChild(2); // lng2
        ScalarOperator arg3 = callOp.getChild(3); // lat2

        double constLng;
        double constLat;

        boolean pair1IsCol = referencesIndexedColumn(arg0, colRefMap, s2IndexedColumnIds) &&
                referencesIndexedColumn(arg1, colRefMap, s2IndexedColumnIds);
        boolean pair2IsConst = (arg2 instanceof ConstantOperator) && (arg3 instanceof ConstantOperator);

        boolean pair2IsCol = referencesIndexedColumn(arg2, colRefMap, s2IndexedColumnIds) &&
                referencesIndexedColumn(arg3, colRefMap, s2IndexedColumnIds);
        boolean pair1IsConst = (arg0 instanceof ConstantOperator) && (arg1 instanceof ConstantOperator);

        if (pair1IsCol && pair2IsConst) {
            constLng = ((ConstantOperator) arg2).getDouble();
            constLat = ((ConstantOperator) arg3).getDouble();
        } else if (pair2IsCol && pair1IsConst) {
            constLng = ((ConstantOperator) arg0).getDouble();
            constLat = ((ConstantOperator) arg1).getDouble();
        } else {
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
    public static S2SearchOptions buildDefaultOptions(List<Index> s2Indexes) {
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
     * Checks if a scalar operator is a spatial function call that references S2-indexed columns.
     */
    public static boolean isSpatialFunctionOnIndexedColumns(CallOperator callOp,
                                                             Map<ColumnRefOperator, Column> colRefMap,
                                                             Set<ColumnId> s2IndexedColumnIds) {
        String fnName = callOp.getFnName();
        if (!ST_CONTAINS.equalsIgnoreCase(fnName) && !ST_DISTANCE_SPHERE.equalsIgnoreCase(fnName)) {
            return false;
        }
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
    public static boolean referencesIndexedColumn(ScalarOperator operator,
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
