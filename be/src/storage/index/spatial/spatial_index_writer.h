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
#include <map>
#include <memory>
#include <string>

#include <roaring/roaring.hh>

namespace starrocks {

class Status;
class WritableFile;

// SpatialIndexWriter builds an S2 cell-to-rowid bitmap index during segment writing.
//
// For each (lng, lat) pair, it computes an S2CellId at the configured level and
// records the row's ordinal in a Roaring bitmap keyed by cell ID. On finish(),
// the mapping is serialized to a standalone file (.si).
//
// File format:
//   [magic: 4 bytes "SPIX"]
//   [version: 4 bytes]
//   [cell_level: 4 bytes]
//   [cell_count: 4 bytes]
//   [cell_entry]...
//
// Each cell_entry:
//   [cell_id: 8 bytes]
//   [bitmap_size: 4 bytes]
//   [bitmap_data: bitmap_size bytes]
class SpatialIndexWriter {
public:
    SpatialIndexWriter(int cell_level, const std::string& file_path);
    ~SpatialIndexWriter() = default;

    Status init();

    // Append a batch of (lng, lat) pairs. row_offset is the starting row ordinal.
    Status append(const double* lng_data, const double* lat_data, uint32_t num_rows, uint32_t row_offset);

    // Finalize and write the index to disk. Sets index_size to bytes written.
    Status finish(uint64_t* index_size);

    uint64_t total_rows() const { return _total_rows; }

    static constexpr uint32_t MAGIC = 0x58495053; // "SPIX" in little-endian
    static constexpr uint32_t VERSION = 1;

private:
    int _cell_level;
    std::string _file_path;
    uint64_t _total_rows = 0;

    // cell_id -> roaring bitmap of row ordinals
    std::map<uint64_t, roaring::Roaring> _cell_bitmaps;
};

} // namespace starrocks
