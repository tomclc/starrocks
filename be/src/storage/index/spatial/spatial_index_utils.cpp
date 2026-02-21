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

// S2 headers MUST be included before any StarRocks headers to avoid
// conflicting definitions in gutil/ vs s2/base/ (integral_types, endian, etc.)
#include <s2/s1angle.h>
#include <s2/s2cap.h>
#include <s2/s2cell_id.h>
#include <s2/s2earth.h>
#include <s2/s2latlng.h>
#include <s2/s2polygon.h>
#include <s2/s2region_coverer.h>

#include "storage/index/spatial/spatial_index_utils.h"

#include "geo/geo_types.h"
#include "geo/wkt_parse.h"

namespace starrocks {

uint64_t SpatialIndexUtils::compute_cell_id(double lng, double lat, int level) {
    S2LatLng ll = S2LatLng::FromDegrees(lat, lng);
    return S2CellId(ll).parent(level).id();
}

std::vector<uint64_t> SpatialIndexUtils::compute_covering_from_wkt(const std::string& wkt, int max_cells,
                                                                    int cell_level) {
    std::vector<uint64_t> result;

    GeoParseStatus status;
    std::unique_ptr<GeoShape> shape(GeoShape::from_wkt(wkt.data(), wkt.size(), &status));
    if (shape == nullptr || status != GEO_PARSE_OK) {
        return result;
    }

    if (shape->type() != GEO_SHAPE_POLYGON) {
        return result;
    }

    auto* polygon = static_cast<GeoPolygon*>(shape.get());
    const S2Polygon* s2_polygon = polygon->polygon();
    if (s2_polygon == nullptr) {
        return result;
    }

    S2RegionCoverer coverer;
    coverer.mutable_options()->set_max_cells(max_cells);
    coverer.mutable_options()->set_fixed_level(cell_level);

    std::vector<S2CellId> covering;
    coverer.GetCovering(*s2_polygon, &covering);

    result.reserve(covering.size());
    for (const auto& cell : covering) {
        result.push_back(cell.id());
    }
    return result;
}

std::vector<uint64_t> SpatialIndexUtils::compute_covering_from_cap(double center_lng, double center_lat,
                                                                    double radius_meters, int max_cells,
                                                                    int cell_level) {
    S2LatLng center = S2LatLng::FromDegrees(center_lat, center_lng);
    S1Angle radius = S2Earth::ToAngle(util::units::Meters(radius_meters));
    S2Cap cap(center.ToPoint(), radius);

    S2RegionCoverer coverer;
    coverer.mutable_options()->set_max_cells(max_cells);
    coverer.mutable_options()->set_fixed_level(cell_level);

    std::vector<S2CellId> covering;
    coverer.GetCovering(cap, &covering);

    std::vector<uint64_t> result;
    result.reserve(covering.size());
    for (const auto& cell : covering) {
        result.push_back(cell.id());
    }
    return result;
}

} // namespace starrocks
