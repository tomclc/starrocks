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

#include <fmt/format.h>

#include <cstdint>
#include <string>

namespace starrocks {

// Returns the path of the S2 index sidecar file for an Iceberg data file.
// The sidecar is stored alongside the data file on the same remote filesystem.
// Example: /path/to/data.parquet.__s2_idx_12345.parquet
static inline std::string iceberg_s2_index_path(const std::string& data_file_path, int64_t index_id) {
    return fmt::format("{}.__s2_idx_{}.parquet", data_file_path, index_id);
}

} // namespace starrocks
