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
#include <vector>

#include "common/status.h"
#include "storage/index/s2/s2_index_reader.h"

namespace starrocks {

// Compute S2 cell ID ranges for a circular region (spherical cap).
// The result is a set of S2CellIdRange intervals whose union covers the cap.
Status compute_cell_ranges_for_cap(double lat, double lng, double radius_meters, int cell_level, int max_cells,
                                   std::vector<S2CellIdRange>* ranges);

// Compute S2 cell ID ranges for a single point.
Status compute_cell_ranges_for_point(double lat, double lng, int cell_level, std::vector<S2CellIdRange>* ranges);

} // namespace starrocks
