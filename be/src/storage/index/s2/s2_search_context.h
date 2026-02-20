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

#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "storage/index/s2/s2_index_reader.h"

namespace starrocks {

struct S2SearchContext {
    bool use_s2_index = false;
    std::shared_ptr<S2IndexReader> reader;
    int32_t cell_level = 15;
    int32_t max_cells = 8; // for S2RegionCoverer

    // Query cell ranges pushed down from the optimizer
    std::vector<S2CellIdRange> query_cell_ranges;
};

} // namespace starrocks
