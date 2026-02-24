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
import com.starrocks.catalog.ExternalTableIndexManager;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.Index;
import com.starrocks.common.Config;
import com.starrocks.common.S2SearchOptions;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalIcebergScanOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rewrites spatial predicates on Iceberg scan operators to leverage S2 cell indexes.
 *
 * Mirrors RewriteToS2PlanRule but matches LOGICAL_ICEBERG_SCAN and looks up indexes
 * from ExternalTableIndexManager rather than the OlapTable.
 */
public class RewriteToS2PlanRuleForIceberg extends TransformationRule {

    public RewriteToS2PlanRuleForIceberg() {
        super(RuleType.TF_S2_ICEBERG_REWRITE_RULE,
                Pattern.create(OperatorType.LOGICAL_ICEBERG_SCAN));
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        if (!Config.enable_experimental_s2) {
            return false;
        }

        LogicalIcebergScanOperator scanOp = (LogicalIcebergScanOperator) input.getOp();
        if (!(scanOp.getTable() instanceof IcebergTable)) {
            return false;
        }

        // Already rewritten
        if (scanOp.getS2SearchOptions() != null) {
            return false;
        }

        // Must have a predicate
        if (scanOp.getPredicate() == null) {
            return false;
        }

        // Check if table has S2 indexes in ExternalTableIndexManager
        IcebergTable table = (IcebergTable) scanOp.getTable();
        ExternalTableIndexManager indexManager = GlobalStateMgr.getCurrentState().getExternalTableIndexManager();
        if (indexManager == null) {
            return false;
        }
        return indexManager.hasS2Index(
                table.getCatalogName(), table.getCatalogDBName(), table.getCatalogTableName());
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalIcebergScanOperator scanOp = (LogicalIcebergScanOperator) input.getOp();
        IcebergTable table = (IcebergTable) scanOp.getTable();

        ExternalTableIndexManager indexManager = GlobalStateMgr.getCurrentState().getExternalTableIndexManager();
        List<Index> s2Indexes = indexManager.getS2Indexes(
                table.getCatalogName(), table.getCatalogDBName(), table.getCatalogTableName());

        if (s2Indexes.isEmpty()) {
            return List.of();
        }

        // Get all indexed column IDs
        Set<ColumnId> s2IndexedColumnIds = s2Indexes.stream()
                .flatMap(idx -> idx.getColumns().stream())
                .collect(Collectors.toSet());

        // Extract spatial parameters from predicates
        ScalarOperator predicate = scanOp.getPredicate();
        S2SearchOptions options = S2SpatialParamExtractor.extractFromConjuncts(
                predicate, scanOp.getColRefToColumnMetaMap(), s2IndexedColumnIds, s2Indexes);

        if (options == null) {
            return List.of();
        }

        // Set S2 search options on the scan operator, keeping the original predicate
        LogicalIcebergScanOperator newScanOp = new LogicalIcebergScanOperator.Builder()
                .withOperator(scanOp)
                .setS2SearchOptions(options)
                .build();

        return List.of(OptExpression.create(newScanOp));
    }
}
