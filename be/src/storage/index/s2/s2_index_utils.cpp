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

#include "storage/index/s2/s2_index_utils.h"

#include "storage/index/s2/s2_cell_ops.h"

namespace starrocks {

Status compute_cell_ranges_for_cap(double lat, double lng, double radius_meters, int cell_level, int max_cells,
                                   std::vector<S2CellIdRange>* ranges) {
    if (radius_meters <= 0) {
        return compute_cell_ranges_for_point(lat, lng, cell_level, ranges);
    }

    auto covering = s2_ops::compute_cap_covering(lat, lng, radius_meters, cell_level, max_cells);
    ranges->reserve(covering.size());
    for (const auto& [min_id, max_id] : covering) {
        S2CellIdRange range;
        range.min_cell_id = min_id;
        range.max_cell_id = max_id;
        ranges->push_back(range);
    }

    return Status::OK();
}

Status compute_cell_ranges_for_point(double lat, double lng, int cell_level, std::vector<S2CellIdRange>* ranges) {
    auto [min_id, max_id] = s2_ops::point_cell_range(lat, lng, cell_level);

    S2CellIdRange range;
    range.min_cell_id = min_id;
    range.max_cell_id = max_id;
    ranges->push_back(range);

    return Status::OK();
}

} // namespace starrocks
