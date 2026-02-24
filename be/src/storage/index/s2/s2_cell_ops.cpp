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

// This file includes ONLY S2 geometry headers and NO StarRocks headers.
// S2 geometry library ships its own copies of Google-internal utility headers
// (endian.h, casts.h, bits.h, port.h) that conflict with StarRocks' gutil.
// Keeping S2 usage in an isolated TU avoids these symbol collisions.

#include "storage/index/s2/s2_cell_ops.h"

#include <s2/s1angle.h>
#include <s2/s2cap.h>
#include <s2/s2cell_id.h>
#include <s2/s2cell_union.h>
#include <s2/s2latlng.h>
#include <s2/s2region_coverer.h>

static constexpr double kEarthRadiusMeters = 6371000.0;

namespace starrocks::s2_ops {

int64_t lat_lng_to_cell_id(double lat_degrees, double lng_degrees, int cell_level) {
    return static_cast<int64_t>(S2CellId(S2LatLng::FromDegrees(lat_degrees, lng_degrees)).parent(cell_level).id());
}

std::vector<std::pair<int64_t, int64_t>> compute_cap_covering(double lat_degrees, double lng_degrees,
                                                               double radius_meters, int cell_level, int max_cells) {
    // S2RegionCoverer's internal exploration becomes very expensive when covering
    // a large region at a fine cell level. Empirically, a cap whose angular radius
    // exceeds ~20 degrees (~2200 km) at level 15 can allocate gigabytes of memory.
    // For such large regions, skip index pruning and let the residual filter handle it.
    static constexpr double kMaxUsefulRadiusMeters = 2000000.0; // 2000 km
    if (radius_meters > kMaxUsefulRadiusMeters) {
        return {};
    }

    S1Angle radius_angle = S1Angle::Radians(radius_meters / kEarthRadiusMeters);
    S2Point center = S2LatLng::FromDegrees(lat_degrees, lng_degrees).ToPoint();
    S2Cap cap(center, radius_angle);

    S2RegionCoverer::Options options;
    options.set_max_level(cell_level);
    options.set_min_level(cell_level);
    options.set_max_cells(max_cells);
    S2RegionCoverer coverer(options);

    S2CellUnion covering = coverer.GetCovering(cap);

    std::vector<std::pair<int64_t, int64_t>> ranges;
    ranges.reserve(covering.size());
    for (const S2CellId& cell : covering) {
        ranges.emplace_back(static_cast<int64_t>(cell.range_min().id()),
                            static_cast<int64_t>(cell.range_max().id()));
    }
    return ranges;
}

std::pair<int64_t, int64_t> point_cell_range(double lat_degrees, double lng_degrees, int cell_level) {
    int64_t id = static_cast<int64_t>(S2CellId(S2LatLng::FromDegrees(lat_degrees, lng_degrees)).parent(cell_level).id());
    return {id, id};
}

} // namespace starrocks::s2_ops
