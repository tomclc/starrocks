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

public class S2IndexParams {

    public enum CommonIndexParamKey implements ParamsKey {
        // S2 cell level controls spatial resolution.
        // Level 15 ~ 1m resolution, level 30 ~ max resolution.
        // Range: 1-30, default: 15
        CELL_LEVEL {
            @Override
            public void check(String value) {
                validateInteger(value, "CELL_LEVEL", 1, 30);
            }
        }
    }

    public enum IndexParamsKey implements ParamsKey {
        // No index-time params initially
    }

    public enum SearchParamsKey implements ParamsKey {
        // Maximum number of S2 cells used by S2RegionCoverer for query region approximation.
        // Higher values = more precise coverage but more cell ranges to check.
        // Range: 1-1000, default: 8
        MAX_CELLS {
            @Override
            public void check(String value) {
                validateInteger(value, "MAX_CELLS", 1, 1000);
            }
        }
    }

    private static void validateInteger(String value, String key, int min, int max) {
        try {
            int num = Integer.parseInt(value);
            if (num < min || num > max) {
                throw new SemanticException(
                        String.format("Value of `%s` must be in range [%d, %d]", key, min, max));
            }
        } catch (NumberFormatException e) {
            throw new SemanticException(String.format("Value of `%s` must be an integer", key));
        }
    }
}
