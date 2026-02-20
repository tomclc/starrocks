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

// S2 headers MUST be included before any StarRocks headers that pull in
// gutil/integral_types.h, due to conflicting int64/uint64 typedefs.
#include <s2/s2cell_id.h>
#include <s2/s2latlng.h>

#include "storage/index/s2/s2_cell_helper.h"

namespace starrocks {

uint64_t compute_s2_cell_id(double lat_degrees, double lng_degrees, int cell_level) {
    S2CellId cell_id = S2CellId(S2LatLng::FromDegrees(lat_degrees, lng_degrees)).parent(cell_level);
    return cell_id.id();
}

} // namespace starrocks
