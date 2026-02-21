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
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.Index;
import com.starrocks.catalog.OlapTable;
import com.starrocks.common.Config;
import com.starrocks.common.SpatialSearchOptions;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

public class RewriteToSpatialPlanRule extends TransformationRule {

    public RewriteToSpatialPlanRule() {
        super(RuleType.TF_REWRITE_TO_SPATIAL_PLAN,
                Pattern.create(OperatorType.LOGICAL_OLAP_SCAN));
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        if (!Config.enable_spatial_index) {
            return false;
        }
        LogicalOlapScanOperator scan = (LogicalOlapScanOperator) input.getOp();
        if (scan.getPredicate() == null) {
            return false;
        }
        OlapTable table = (OlapTable) scan.getTable();
        // Check if table has any SPATIAL index
        return table.getIndexes().stream()
                .anyMatch(idx -> idx.getIndexType() == IndexDef.IndexType.SPATIAL);
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalOlapScanOperator scan = (LogicalOlapScanOperator) input.getOp();
        OlapTable table = (OlapTable) scan.getTable();

        ScalarOperator predicate = scan.getPredicate();
        if (predicate == null) {
            return Collections.emptyList();
        }

        // Extract conjuncts and look for spatial predicates among them
        List<ScalarOperator> conjuncts = Utils.extractConjuncts(predicate);
        for (ScalarOperator conjunct : conjuncts) {
            SpatialSearchOptions options = tryExtractSpatialOptions(conjunct, table);
            if (options != null) {
                scan.setSpatialSearchOptions(options);

                // Inject bounding box predicates on lng/lat columns for zone map evaluation
                List<ScalarOperator> bboxPredicates = buildBoundingBoxPredicates(options, table, scan);
                if (!bboxPredicates.isEmpty()) {
                    List<ScalarOperator> allConjuncts = new ArrayList<>(conjuncts);
                    allConjuncts.addAll(bboxPredicates);
                    scan.setPredicate(Utils.compoundAnd(allConjuncts));
                }

                return List.of(input);
            }
        }

        return Collections.emptyList();
    }

    private SpatialSearchOptions tryExtractSpatialOptions(ScalarOperator predicate, OlapTable table) {
        // Look for ST_Contains(polygon_expr, ST_Point(col, col)) pattern
        if (predicate instanceof CallOperator) {
            CallOperator call = (CallOperator) predicate;
            String fnName = call.getFnName();

            if (FunctionSet.ST_CONTAINS.equalsIgnoreCase(fnName) && call.getChildren().size() == 2) {
                return tryExtractContainsPredicate(call, table);
            }
        }

        // Look for ST_Distance_Sphere(...) < radius pattern
        if (predicate instanceof BinaryPredicateOperator) {
            BinaryPredicateOperator binOp = (BinaryPredicateOperator) predicate;
            return tryExtractDistancePredicate(binOp, table);
        }

        return null;
    }

    private SpatialSearchOptions tryExtractContainsPredicate(CallOperator call, OlapTable table) {
        // ST_Contains(ST_Polygon('POLYGON(...)'), ST_Point(lng_col, lat_col))
        ScalarOperator arg0 = call.getChild(0);
        ScalarOperator arg1 = call.getChild(1);

        // Check if arg0 is a constant polygon (ST_Polygon with literal WKT)
        String wkt = extractConstantWkt(arg0);
        if (wkt == null) {
            return null;
        }

        // Check if arg1 references columns with a spatial index
        if (!referencesSpatialIndexedColumns(arg1, table)) {
            return null;
        }

        SpatialSearchOptions opts = new SpatialSearchOptions();
        opts.setEnableSpatialIndex(true);
        opts.setPredicateType("contains");
        opts.setQueryWkt(wkt);
        return opts;
    }

    private SpatialSearchOptions tryExtractDistancePredicate(BinaryPredicateOperator binOp, OlapTable table) {
        // ST_Distance_Sphere(col, col, const, const) < const_radius
        ScalarOperator left = binOp.getChild(0);
        ScalarOperator right = binOp.getChild(1);

        // Only handle < and <= for distance
        if (binOp.getBinaryType() != BinaryType.LT &&
                binOp.getBinaryType() != BinaryType.LE) {
            return null;
        }

        CallOperator distCall = null;
        ConstantOperator radiusConst = null;

        if (left instanceof CallOperator && right instanceof ConstantOperator) {
            distCall = (CallOperator) left;
            radiusConst = (ConstantOperator) right;
        } else {
            return null;
        }

        if (!FunctionSet.ST_DISTANCE_SPHERE.equalsIgnoreCase(distCall.getFnName())) {
            return null;
        }

        if (distCall.getChildren().size() != 4) {
            return null;
        }

        // Extract center coordinates (args 2 and 3 should be constants)
        ScalarOperator arg2 = distCall.getChild(2);
        ScalarOperator arg3 = distCall.getChild(3);

        if (!(arg2 instanceof ConstantOperator) || !(arg3 instanceof ConstantOperator)) {
            return null;
        }

        try {
            double centerLng = ((ConstantOperator) arg2).getDouble();
            double centerLat = ((ConstantOperator) arg3).getDouble();
            double radius = radiusConst.getDouble();

            SpatialSearchOptions opts = new SpatialSearchOptions();
            opts.setEnableSpatialIndex(true);
            opts.setPredicateType("distance");
            opts.setCenterLng(centerLng);
            opts.setCenterLat(centerLat);
            opts.setRadiusMeters(radius);
            return opts;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractConstantWkt(ScalarOperator op) {
        if (op instanceof CallOperator) {
            CallOperator call = (CallOperator) op;
            if (FunctionSet.ST_POLYGON.equalsIgnoreCase(call.getFnName()) ||
                    FunctionSet.ST_GEOMFROMTEXT.equalsIgnoreCase(call.getFnName()) ||
                    FunctionSet.ST_GEOMETRYFROMTEXT.equalsIgnoreCase(call.getFnName())) {
                if (call.getChildren().size() == 1 && call.getChild(0) instanceof ConstantOperator) {
                    return ((ConstantOperator) call.getChild(0)).getVarchar();
                }
            }
        }
        // If it is already a constant (pre-evaluated)
        if (op instanceof ConstantOperator && op.getType().isStringType()) {
            return ((ConstantOperator) op).getVarchar();
        }
        return null;
    }

    private boolean referencesSpatialIndexedColumns(ScalarOperator op, OlapTable table) {
        // Simple check -- verify the table has a spatial index
        // More precise column matching can be added later
        return table.getIndexes().stream()
                .anyMatch(idx -> idx.getIndexType() == IndexDef.IndexType.SPATIAL);
    }

    /**
     * Build bounding box range predicates on lng/lat columns.
     * These predicates enable zone map page-skipping after geographic compaction sort.
     */
    private List<ScalarOperator> buildBoundingBoxPredicates(
            SpatialSearchOptions options, OlapTable table, LogicalOlapScanOperator scan) {
        // Find spatial index and its lng/lat columns
        Index spatialIndex = table.getIndexes().stream()
                .filter(idx -> idx.getIndexType() == IndexDef.IndexType.SPATIAL)
                .findFirst().orElse(null);
        if (spatialIndex == null || spatialIndex.getColumns().size() < 2) {
            return Collections.emptyList();
        }

        ColumnId lngColId = spatialIndex.getColumns().get(0);
        ColumnId latColId = spatialIndex.getColumns().get(1);
        Column lngCol = table.getColumn(lngColId);
        Column latCol = table.getColumn(latColId);
        if (lngCol == null || latCol == null) {
            return Collections.emptyList();
        }

        // Get ColumnRefOperators for lng/lat
        Map<Column, ColumnRefOperator> colRefMap = scan.getColumnMetaToColRefMap();
        ColumnRefOperator lngRef = colRefMap.get(lngCol);
        ColumnRefOperator latRef = colRefMap.get(latCol);
        if (lngRef == null || latRef == null) {
            return Collections.emptyList();
        }

        // Compute bounding box based on predicate type
        double[] bbox; // [minLng, maxLng, minLat, maxLat]
        if ("contains".equals(options.getPredicateType())) {
            bbox = computeBboxFromWkt(options.getQueryWkt());
        } else if ("distance".equals(options.getPredicateType())) {
            bbox = computeBboxFromRadius(options.getCenterLng(), options.getCenterLat(),
                    options.getRadiusMeters());
        } else {
            return Collections.emptyList();
        }

        if (bbox == null) {
            return Collections.emptyList();
        }

        // Create: lng >= minLng AND lng <= maxLng AND lat >= minLat AND lat <= maxLat
        List<ScalarOperator> predicates = new ArrayList<>(4);
        try {
            predicates.add(new BinaryPredicateOperator(BinaryType.GE,
                    lngRef, ConstantOperator.createDouble(bbox[0])));
            predicates.add(new BinaryPredicateOperator(BinaryType.LE,
                    lngRef, ConstantOperator.createDouble(bbox[1])));
            predicates.add(new BinaryPredicateOperator(BinaryType.GE,
                    latRef, ConstantOperator.createDouble(bbox[2])));
            predicates.add(new BinaryPredicateOperator(BinaryType.LE,
                    latRef, ConstantOperator.createDouble(bbox[3])));
        } catch (Exception e) {
            return Collections.emptyList();
        }
        return predicates;
    }

    /**
     * Parse WKT polygon string to extract bounding box [minLng, maxLng, minLat, maxLat].
     * Handles POLYGON((lng lat, lng lat, ...)) format.
     */
    private double[] computeBboxFromWkt(String wkt) {
        if (wkt == null || wkt.isEmpty()) {
            return null;
        }
        try {
            // Extract coordinate pairs from POLYGON((x y, x y, ...))
            java.util.regex.Pattern coordPattern = java.util.regex.Pattern.compile(
                    "(-?[\\d.]+)\\s+(-?[\\d.]+)");
            Matcher matcher = coordPattern.matcher(wkt);
            double minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            boolean found = false;
            while (matcher.find()) {
                double lng = Double.parseDouble(matcher.group(1));
                double lat = Double.parseDouble(matcher.group(2));
                minLng = Math.min(minLng, lng);
                maxLng = Math.max(maxLng, lng);
                minLat = Math.min(minLat, lat);
                maxLat = Math.max(maxLat, lat);
                found = true;
            }
            if (!found) {
                return null;
            }
            // Clamp to valid ranges
            minLng = Math.max(minLng, -180.0);
            maxLng = Math.min(maxLng, 180.0);
            minLat = Math.max(minLat, -90.0);
            maxLat = Math.min(maxLat, 90.0);
            return new double[]{minLng, maxLng, minLat, maxLat};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compute bounding box from center point and radius (meters).
     * Uses approximate conversion: 1 degree latitude ~= 111,320 meters.
     */
    private double[] computeBboxFromRadius(double centerLng, double centerLat, double radiusMeters) {
        if (radiusMeters <= 0) {
            return null;
        }
        // Approximate degrees per meter at given latitude
        double latDegPerMeter = 1.0 / 111320.0;
        double lngDegPerMeter = 1.0 / (111320.0 * Math.cos(Math.toRadians(centerLat)));

        double dLat = radiusMeters * latDegPerMeter;
        double dLng = radiusMeters * lngDegPerMeter;

        double minLng = Math.max(centerLng - dLng, -180.0);
        double maxLng = Math.min(centerLng + dLng, 180.0);
        double minLat = Math.max(centerLat - dLat, -90.0);
        double maxLat = Math.min(centerLat + dLat, 90.0);

        return new double[]{minLng, maxLng, minLat, maxLat};
    }
}
