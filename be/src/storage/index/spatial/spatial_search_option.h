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

struct SpatialSearchOption {
    bool enable_spatial_index = false;
    std::string query_wkt;
    double center_lng = 0.0;
    double center_lat = 0.0;
    double radius_meters = 0.0;
    std::string predicate_type; // "contains", "distance", "knn"
    int64_t knn_k = 0;
    // Runtime spatial filter for joins: multiple WKT regions from build side
    bool is_runtime_spatial_filter = false;
    std::vector<std::string> runtime_wkt_regions;
};

using SpatialSearchOptionPtr = std::shared_ptr<SpatialSearchOption>;

} // namespace starrocks
