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
#include <string>
#include <vector>

namespace starrocks {

// Utility functions for spatial indexing using S2 geometry.
// All S2 types are kept internal — public APIs use uint64_t cell IDs.
class SpatialIndexUtils {
public:
    // Compute S2CellId (as uint64_t) for a (lng, lat) pair at the given level
    static uint64_t compute_cell_id(double lng, double lat, int level);

    // Compute S2 covering cell IDs (as uint64_t) for a polygon given as WKT
    static std::vector<uint64_t> compute_covering_from_wkt(const std::string& wkt, int max_cells, int cell_level);

    // Compute S2 covering cell IDs (as uint64_t) for a spherical cap (center + radius)
    static std::vector<uint64_t> compute_covering_from_cap(double center_lng, double center_lat, double radius_meters,
                                                           int max_cells, int cell_level);

    // Validate lng/lat range
    static bool is_valid_lng_lat(double lng, double lat) { return lng >= -180.0 && lng <= 180.0 && lat >= -90.0 && lat <= 90.0; }
};

} // namespace starrocks
