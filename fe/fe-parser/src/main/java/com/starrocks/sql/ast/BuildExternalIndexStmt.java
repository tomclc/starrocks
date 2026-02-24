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

package com.starrocks.sql.ast;

import com.starrocks.sql.parser.NodePosition;

/**
 * BUILD INDEX index_name ON [catalog.]db.table
 *
 * Triggers building of S2 spatial index sidecar files for external tables.
 * The index must have been previously created via CREATE INDEX.
 */
public class BuildExternalIndexStmt extends DdlStmt {
    private final String indexName;
    private final TableRef tableRef;

    public BuildExternalIndexStmt(String indexName, TableRef tableRef, NodePosition pos) {
        super(pos);
        this.indexName = indexName;
        this.tableRef = tableRef;
    }

    public String getIndexName() {
        return indexName;
    }

    public TableRef getTableRef() {
        return tableRef;
    }

    public String getCatalogName() {
        return tableRef.getCatalogName();
    }

    public String getDbName() {
        return tableRef.getDbName();
    }

    public String getTableName() {
        return tableRef.getTableName();
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitBuildExternalIndexStatement(this, context);
    }
}
