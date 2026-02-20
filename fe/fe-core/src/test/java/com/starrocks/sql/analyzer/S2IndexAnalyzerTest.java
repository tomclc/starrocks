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

package com.starrocks.sql.analyzer;

import com.google.common.collect.Lists;
import com.starrocks.catalog.Column;
import com.starrocks.common.Config;
import com.starrocks.sql.ast.IndexDef;
import com.starrocks.sql.ast.KeysType;
import com.starrocks.type.DoubleType;
import com.starrocks.type.IntegerType;
import com.starrocks.type.ScalarType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.starrocks.sql.ast.ColumnDef.DefaultValueDef.NULL_DEFAULT_VALUE;

public class S2IndexAnalyzerTest {

    @BeforeEach
    public void setUp() {
        Config.enable_experimental_s2 = true;
    }

    @AfterEach
    public void tearDown() {
        Config.enable_experimental_s2 = false;
    }

    @Test
    public void testAnalyzeS2IndexSingleColumn() {
        // S2 index with 1 column should parse successfully
        IndexDef indexDef = new IndexDef("s2_idx", Lists.newArrayList("geo_col"), IndexDef.IndexType.S2, "");
        IndexAnalyzer.analyze(indexDef);
    }

    @Test
    public void testAnalyzeS2IndexTwoColumns() {
        // S2 index with 2 columns (lat, lng) should parse successfully
        IndexDef indexDef = new IndexDef("s2_idx", Lists.newArrayList("lat", "lng"), IndexDef.IndexType.S2, "");
        IndexAnalyzer.analyze(indexDef);
    }

    @Test
    public void testAnalyzeS2IndexThreeColumnsRejected() {
        // S2 index with 3 columns should be rejected
        IndexDef indexDef = new IndexDef("s2_idx", Lists.newArrayList("a", "b", "c"), IndexDef.IndexType.S2, "");
        Assertions.assertThrows(SemanticException.class,
                () -> IndexAnalyzer.analyze(indexDef));
    }

    @Test
    public void testCheckS2IndexWithDoubleColumn() {
        // DOUBLE column should be valid for S2 index (lat/lng mode)
        Column column = new Column("lat", DoubleType.DOUBLE, false, null, true, NULL_DEFAULT_VALUE, "");
        Map<String, String> properties = new HashMap<>();
        IndexAnalyzer.checkS2IndexValid(column, properties, KeysType.DUP_KEYS);
    }

    @Test
    public void testCheckS2IndexWithVarcharColumn() {
        // VARCHAR column should be valid for S2 index (WKT mode)
        Column column = new Column("geo", ScalarType.createVarcharType(65535), false, null, true, NULL_DEFAULT_VALUE, "");
        Map<String, String> properties = new HashMap<>();
        IndexAnalyzer.checkS2IndexValid(column, properties, KeysType.DUP_KEYS);
    }

    @Test
    public void testCheckS2IndexWithIntColumnRejected() {
        // INT column should be rejected for S2 index
        Column column = new Column("id", IntegerType.INT, false, null, true, NULL_DEFAULT_VALUE, "");
        Map<String, String> properties = new HashMap<>();
        Assertions.assertThrows(SemanticException.class,
                () -> IndexAnalyzer.checkS2IndexValid(column, properties, KeysType.DUP_KEYS));
    }

    @Test
    public void testCheckS2IndexDisabledByConfig() {
        Config.enable_experimental_s2 = false;
        Column column = new Column("lat", DoubleType.DOUBLE, false, null, true, NULL_DEFAULT_VALUE, "");
        Map<String, String> properties = new HashMap<>();
        Assertions.assertThrows(SemanticException.class,
                () -> IndexAnalyzer.checkS2IndexValid(column, properties, KeysType.DUP_KEYS));
    }

    @Test
    public void testCheckS2IndexAggKeysRejected() {
        // AGG_KEYS table should be rejected for S2 index
        Column column = new Column("lat", DoubleType.DOUBLE, false, null, true, NULL_DEFAULT_VALUE, "");
        Map<String, String> properties = new HashMap<>();
        Assertions.assertThrows(SemanticException.class,
                () -> IndexAnalyzer.checkS2IndexValid(column, properties, KeysType.AGG_KEYS));
    }

    @Test
    public void testCheckS2IndexPrimaryKeys() {
        // PRIMARY_KEYS table should be valid for S2 index
        Column column = new Column("lat", DoubleType.DOUBLE, false, null, true, NULL_DEFAULT_VALUE, "");
        Map<String, String> properties = new HashMap<>();
        IndexAnalyzer.checkS2IndexValid(column, properties, KeysType.PRIMARY_KEYS);
    }

    @Test
    public void testCheckS2IndexCellLevelValidation() {
        Column column = new Column("lat", DoubleType.DOUBLE, false, null, true, NULL_DEFAULT_VALUE, "");

        // Valid cell_level
        Map<String, String> properties1 = new HashMap<>();
        properties1.put("CELL_LEVEL", "20");
        IndexAnalyzer.checkS2IndexValid(column, properties1, KeysType.DUP_KEYS);

        // Invalid cell_level (too high)
        Map<String, String> properties2 = new HashMap<>();
        properties2.put("CELL_LEVEL", "31");
        Assertions.assertThrows(SemanticException.class,
                () -> IndexAnalyzer.checkS2IndexValid(column, properties2, KeysType.DUP_KEYS));

        // Invalid cell_level (too low)
        Map<String, String> properties3 = new HashMap<>();
        properties3.put("CELL_LEVEL", "0");
        Assertions.assertThrows(SemanticException.class,
                () -> IndexAnalyzer.checkS2IndexValid(column, properties3, KeysType.DUP_KEYS));

        // Invalid cell_level (not a number)
        Map<String, String> properties4 = new HashMap<>();
        properties4.put("CELL_LEVEL", "abc");
        Assertions.assertThrows(SemanticException.class,
                () -> IndexAnalyzer.checkS2IndexValid(column, properties4, KeysType.DUP_KEYS));
    }

    @Test
    public void testCheckS2IndexDefaultProperties() {
        Column column = new Column("lat", DoubleType.DOUBLE, false, null, true, NULL_DEFAULT_VALUE, "");
        Map<String, String> properties = new HashMap<>();
        IndexAnalyzer.checkS2IndexValid(column, properties, KeysType.DUP_KEYS);

        // Should have default cell_level and max_cells added
        Assertions.assertTrue(properties.containsKey("cell_level"));
        Assertions.assertEquals("15", properties.get("cell_level"));
        Assertions.assertTrue(properties.containsKey("max_cells"));
        Assertions.assertEquals("8", properties.get("max_cells"));
    }

    @Test
    public void testIsCompatibleIndex() {
        // S2 should be a compatible index type (uses new metadata system)
        Assertions.assertTrue(IndexDef.IndexType.isCompatibleIndex(IndexDef.IndexType.S2));
    }
}
