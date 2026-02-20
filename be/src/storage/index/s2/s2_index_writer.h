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
#include <string>
#include <utility>
#include <vector>

#include "common/status.h"

namespace starrocks {

class Column;
class TabletIndex;

// S2IndexWriter builds a Parquet sidecar file mapping S2 cell IDs to row IDs.
// The Parquet file is sorted by s2_cell_id, enabling range-based pruning via
// page-level min/max statistics.
//
// Two modes:
// - 2-column mode: append(lat_col, lng_col) where both are DOUBLE columns
// - 1-column mode: append(geo_col) where column is VARCHAR containing WKT text
class S2IndexWriter {
public:
    static Status create(const std::shared_ptr<TabletIndex>& tablet_index, const std::string& index_file_path,
                         std::unique_ptr<S2IndexWriter>* writer);

    S2IndexWriter(std::shared_ptr<TabletIndex> tablet_index, std::string index_file_path);
    ~S2IndexWriter() = default;

    Status init();

    // Append lat/lng double column pair (2-column mode)
    Status append_lat_lng(const Column& lat_col, const Column& lng_col, size_t num_rows);

    // Append WKT string column (1-column mode)
    Status append_wkt(const Column& geo_col, size_t num_rows);

    // Finalize: sort by cell_id, write Parquet file
    Status finish(uint64_t* index_size);

private:
    std::shared_ptr<TabletIndex> _tablet_index;
    std::string _index_file_path;
    int32_t _cell_level = 15;

    // Buffered data: pairs of (s2_cell_id, row_id)
    std::vector<std::pair<uint64_t, uint64_t>> _cell_row_pairs;
    uint64_t _current_row_offset = 0;
};

} // namespace starrocks
