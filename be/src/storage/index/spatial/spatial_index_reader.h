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
#include <string>
#include <vector>

#include <roaring/roaring.hh>

namespace starrocks {

class Status;

// SpatialIndexReader loads and queries an S2 cell->rowid bitmap index.
//
// After loading from a .si file, caller provides a set of covering cell IDs
// (as uint64_t values) and receives a union roaring bitmap of candidate rows.
class SpatialIndexReader {
public:
    SpatialIndexReader() = default;
    ~SpatialIndexReader() = default;

    // Load the spatial index from a file
    Status load(const std::string& file_path);

    // Search: union bitmaps for all cells in the covering.
    // Cell IDs are raw S2CellId::id() values (uint64_t).
    roaring::Roaring search(const std::vector<uint64_t>& covering_cell_ids) const;

    // Search with parent/child expansion for better containment coverage
    roaring::Roaring search_with_expansion(const std::vector<uint64_t>& covering_cell_ids) const;

    int cell_level() const { return _cell_level; }
    uint32_t cell_count() const { return static_cast<uint32_t>(_cell_bitmaps.size()); }
    bool loaded() const { return _loaded; }

private:
    bool _loaded = false;
    int _cell_level = 0;
    std::map<uint64_t, roaring::Roaring> _cell_bitmaps;
};

} // namespace starrocks
