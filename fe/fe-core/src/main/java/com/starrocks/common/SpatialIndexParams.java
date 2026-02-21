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

package com.starrocks.common;

import com.starrocks.common.io.ParamsKey;
import com.starrocks.sql.analyzer.SemanticException;

public class SpatialIndexParams {

    public enum CommonIndexParamKey implements ParamsKey {
        S2_CELL_LEVEL {
            @Override
            public void check(String value) {
                try {
                    int level = Integer.parseInt(value);
                    if (level < 1 || level > 30) {
                        throw new SemanticException("Value of `s2_cell_level` must be in range [1, 30]");
                    }
                } catch (NumberFormatException e) {
                    throw new SemanticException("Value of `s2_cell_level` must be an integer");
                }
            }
        }
    }

    public enum IndexParamsKey implements ParamsKey {
        MAX_CELLS_COVERING {
            @Override
            public void check(String value) {
                try {
                    int num = Integer.parseInt(value);
                    if (num < 1 || num > 100) {
                        throw new SemanticException("Value of `max_cells_covering` must be in range [1, 100]");
                    }
                } catch (NumberFormatException e) {
                    throw new SemanticException("Value of `max_cells_covering` must be an integer");
                }
            }
        }
    }
}
