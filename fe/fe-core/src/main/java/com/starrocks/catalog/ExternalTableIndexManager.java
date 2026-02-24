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

package com.starrocks.catalog;

import com.google.gson.annotations.SerializedName;
import com.starrocks.sql.ast.IndexDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Manages S2 spatial indexes on external tables (e.g., Iceberg).
 *
 * Index metadata is stored in StarRocks catalog and persisted via edit log.
 * The actual S2 index sidecar files are stored alongside data files on remote FS.
 *
 * Thread-safe via a read-write lock.
 */
public class ExternalTableIndexManager {

    /**
     * Uniquely identifies an external table across catalogs.
     */
    public static class TableIdentifier {
        @SerializedName("catalogName")
        private final String catalogName;
        @SerializedName("dbName")
        private final String dbName;
        @SerializedName("tableName")
        private final String tableName;

        public TableIdentifier(String catalogName, String dbName, String tableName) {
            this.catalogName = catalogName;
            this.dbName = dbName;
            this.tableName = tableName;
        }

        public String getCatalogName() {
            return catalogName;
        }

        public String getDbName() {
            return dbName;
        }

        public String getTableName() {
            return tableName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TableIdentifier that = (TableIdentifier) o;
            return catalogName.equals(that.catalogName) &&
                    dbName.equals(that.dbName) &&
                    tableName.equals(that.tableName);
        }

        @Override
        public int hashCode() {
            int result = catalogName.hashCode();
            result = 31 * result + dbName.hashCode();
            result = 31 * result + tableName.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return catalogName + "." + dbName + "." + tableName;
        }
    }

    public enum IndexBuildStatus {
        PENDING,
        BUILDING,
        BUILT,
        FAILED
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final AtomicLong nextId = new AtomicLong(1);

    // Table -> list of indexes defined on that table
    private final Map<TableIdentifier, List<Index>> tableIndexes = new ConcurrentHashMap<>();

    // (Table, IndexId) -> (dataFilePath -> build status)
    private final Map<String, Map<String, IndexBuildStatus>> buildStatusMap = new ConcurrentHashMap<>();

    public ExternalTableIndexManager() {
    }

    public long nextIndexId() {
        return nextId.getAndIncrement();
    }

    public void addIndex(TableIdentifier tableId, Index index) {
        lock.writeLock().lock();
        try {
            tableIndexes.computeIfAbsent(tableId, k -> new ArrayList<>()).add(index);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void dropIndex(TableIdentifier tableId, String indexName) {
        lock.writeLock().lock();
        try {
            List<Index> indexes = tableIndexes.get(tableId);
            if (indexes != null) {
                indexes.removeIf(idx -> idx.getIndexName().equalsIgnoreCase(indexName));
                if (indexes.isEmpty()) {
                    tableIndexes.remove(tableId);
                }
            }
            // Clean up build status
            String statusKey = tableId.toString() + "." + indexName;
            buildStatusMap.remove(statusKey);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Index> getIndexes(TableIdentifier tableId) {
        lock.readLock().lock();
        try {
            return tableIndexes.getOrDefault(tableId, Collections.emptyList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Index> getS2Indexes(TableIdentifier tableId) {
        lock.readLock().lock();
        try {
            List<Index> indexes = tableIndexes.get(tableId);
            if (indexes == null) {
                return Collections.emptyList();
            }
            return indexes.stream()
                    .filter(idx -> idx.getIndexType() == IndexDef.IndexType.S2)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Index> getS2Indexes(String catalogName, String dbName, String tableName) {
        return getS2Indexes(new TableIdentifier(catalogName, dbName, tableName));
    }

    public boolean hasS2Index(String catalogName, String dbName, String tableName) {
        return !getS2Indexes(catalogName, dbName, tableName).isEmpty();
    }

    public boolean hasIndex(TableIdentifier tableId, String indexName) {
        lock.readLock().lock();
        try {
            List<Index> indexes = tableIndexes.get(tableId);
            if (indexes == null) {
                return false;
            }
            return indexes.stream().anyMatch(idx -> idx.getIndexName().equalsIgnoreCase(indexName));
        } finally {
            lock.readLock().unlock();
        }
    }

    public void updateBuildStatus(TableIdentifier tableId, long indexId,
                                  String dataFilePath, IndexBuildStatus status) {
        String statusKey = tableId.toString() + "." + indexId;
        buildStatusMap.computeIfAbsent(statusKey, k -> new HashMap<>())
                .put(dataFilePath, status);
    }

    public IndexBuildStatus getBuildStatus(TableIdentifier tableId, long indexId, String dataFilePath) {
        String statusKey = tableId.toString() + "." + indexId;
        Map<String, IndexBuildStatus> fileStatuses = buildStatusMap.get(statusKey);
        if (fileStatuses == null) {
            return IndexBuildStatus.PENDING;
        }
        return fileStatuses.getOrDefault(dataFilePath, IndexBuildStatus.PENDING);
    }

    public Map<TableIdentifier, List<Index>> getAllTableIndexes() {
        lock.readLock().lock();
        try {
            return new HashMap<>(tableIndexes);
        } finally {
            lock.readLock().unlock();
        }
    }
}
