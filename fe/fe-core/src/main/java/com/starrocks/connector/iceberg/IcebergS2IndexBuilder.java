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

package com.starrocks.connector.iceberg;

import com.starrocks.catalog.ExternalTableIndexManager;
import com.starrocks.catalog.ExternalTableIndexManager.IndexBuildStatus;
import com.starrocks.catalog.ExternalTableIndexManager.TableIdentifier;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.Index;
import com.starrocks.catalog.Table;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.BuildExternalIndexStmt;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.io.CloseableIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds S2 spatial index sidecar files for external Iceberg tables.
 *
 * Flow:
 * 1. Validates the index exists in ExternalTableIndexManager
 * 2. Gets the native Iceberg table and lists all data files
 * 3. For each data file without a built sidecar, marks it as PENDING
 * 4. Dispatches build tasks to BEs (future: async job framework)
 */
public class IcebergS2IndexBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(IcebergS2IndexBuilder.class);

    public static void buildIndex(ConnectContext context, BuildExternalIndexStmt stmt) throws DdlException {
        if (!Config.enable_experimental_s2) {
            throw new DdlException("S2 index is disabled. Enable via FE config `enable_experimental_s2`");
        }

        String catalogName = stmt.getCatalogName();
        if (catalogName == null) {
            catalogName = context.getCurrentCatalog();
        }
        String dbName = stmt.getDbName();
        if (dbName == null) {
            dbName = context.getDatabase();
        }
        if (dbName == null) {
            throw new DdlException("No database selected");
        }
        String tableName = stmt.getTableName();
        String indexName = stmt.getIndexName();

        // Validate table exists and is an Iceberg table
        Table table = GlobalStateMgr.getCurrentState().getMetadataMgr()
                .getTable(context, catalogName, dbName, tableName);
        if (table == null) {
            throw new DdlException("Table not found: " + catalogName + "." + dbName + "." + tableName);
        }

        if (!(table instanceof IcebergTable)) {
            throw new DdlException("BUILD INDEX is only supported on Iceberg tables");
        }

        IcebergTable icebergTable = (IcebergTable) table;

        // Validate index exists in ExternalTableIndexManager
        ExternalTableIndexManager indexMgr = GlobalStateMgr.getCurrentState().getExternalTableIndexManager();
        TableIdentifier tableId = new TableIdentifier(catalogName, dbName, tableName);
        List<Index> indexes = indexMgr.getIndexes(tableId);

        Index targetIndex = null;
        for (Index idx : indexes) {
            if (idx.getIndexName().equalsIgnoreCase(indexName)) {
                targetIndex = idx;
                break;
            }
        }

        if (targetIndex == null) {
            throw new DdlException("Index '" + indexName + "' does not exist on table " +
                    catalogName + "." + dbName + "." + tableName +
                    ". Use CREATE INDEX first.");
        }

        // Get native Iceberg table and list data files
        org.apache.iceberg.Table nativeTable = icebergTable.getNativeTable();
        Snapshot currentSnapshot = nativeTable.currentSnapshot();
        if (currentSnapshot == null) {
            LOG.info("Table {}.{}.{} has no snapshots, nothing to build", catalogName, dbName, tableName);
            return;
        }

        List<String> filesToBuild = new ArrayList<>();
        TableScan scan = nativeTable.newScan().useSnapshot(currentSnapshot.snapshotId());

        try (CloseableIterable<FileScanTask> tasks = scan.planFiles()) {
            for (FileScanTask task : tasks) {
                String dataFilePath = task.file().path().toString();
                IndexBuildStatus status = indexMgr.getBuildStatus(tableId, targetIndex.getIndexId(), dataFilePath);
                if (status != IndexBuildStatus.BUILT && status != IndexBuildStatus.BUILDING) {
                    filesToBuild.add(dataFilePath);
                    indexMgr.updateBuildStatus(tableId, targetIndex.getIndexId(),
                            dataFilePath, IndexBuildStatus.PENDING);
                }
            }
        } catch (IOException e) {
            throw new DdlException("Failed to list data files for table " +
                    catalogName + "." + dbName + "." + tableName + ": " + e.getMessage());
        }

        if (filesToBuild.isEmpty()) {
            LOG.info("All data files for index '{}' on {}.{}.{} are already built",
                    indexName, catalogName, dbName, tableName);
            return;
        }

        LOG.info("Found {} data files to build S2 index '{}' (id={}) on {}.{}.{}",
                filesToBuild.size(), indexName, targetIndex.getIndexId(), catalogName, dbName, tableName);

        // TODO: Dispatch build tasks to BEs via TBuildS2IndexRequest RPC
        // For each file in filesToBuild:
        //   1. Select a BE to handle the build
        //   2. Send TBuildS2IndexRequest with data file path, index params, cloud config
        //   3. BE reads the parquet data file, extracts lat/lng columns, builds S2 index sidecar
        //   4. Update build status to BUILT on success, FAILED on failure
        //
        // For now, mark files as BUILDING to indicate the build was initiated.
        for (String filePath : filesToBuild) {
            indexMgr.updateBuildStatus(tableId, targetIndex.getIndexId(), filePath, IndexBuildStatus.BUILDING);
        }

        LOG.info("Initiated S2 index build for {} files. Index: '{}', Table: {}.{}.{}",
                filesToBuild.size(), indexName, catalogName, dbName, tableName);
    }
}
