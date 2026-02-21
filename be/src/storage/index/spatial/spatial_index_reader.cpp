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

#include "storage/index/spatial/spatial_index_reader.h"

#include "common/status.h"
#include "fs/fs.h"
#include "storage/index/spatial/spatial_index_writer.h"
#include "base/coding.h"

namespace starrocks {

// S2CellId bit-level helpers (avoids including S2 headers which conflict with gutil)
namespace {

constexpr int kMaxS2Level = 30;

// The lowest set bit of an S2CellId encodes its level.
inline uint64_t s2cell_lsb(uint64_t id) { return id & (~id + 1); }

inline int s2cell_level(uint64_t id) {
    // The level is determined by the position of the lowest set bit.
    // lsb position = 2 * (30 - level), so level = 30 - (ctz(id) >> 1)
    return kMaxS2Level - (__builtin_ctzll(id) >> 1);
}

inline uint64_t s2cell_lsb_for_level(int level) { return 1ULL << (2 * (kMaxS2Level - level)); }

inline uint64_t s2cell_parent(uint64_t id, int level) {
    uint64_t new_lsb = s2cell_lsb_for_level(level);
    return (id & (~new_lsb + 1)) | new_lsb;
}

inline bool s2cell_contains(uint64_t a, uint64_t b) {
    // Cell A contains cell B iff B is in [A.range_min, A.range_max]
    uint64_t a_lsb = s2cell_lsb(a);
    uint64_t range_min = a - (a_lsb - 1);
    uint64_t range_max = a + (a_lsb - 1);
    return b >= range_min && b <= range_max;
}

} // namespace

Status SpatialIndexReader::load(const std::string& file_path) {
    ASSIGN_OR_RETURN(auto rfile, FileSystem::Default()->new_random_access_file(file_path));
    ASSIGN_OR_RETURN(auto file_size, rfile->get_size());

    if (file_size < 16) {
        return Status::Corruption("Spatial index file too small");
    }

    std::string buf(file_size, '\0');
    RETURN_IF_ERROR(rfile->read_at_fully(0, buf.data(), file_size));

    const uint8_t* data = reinterpret_cast<const uint8_t*>(buf.data());
    size_t offset = 0;

    // Read header
    uint32_t magic = decode_fixed32_le(data + offset);
    offset += 4;
    if (magic != SpatialIndexWriter::MAGIC) {
        return Status::Corruption("Invalid spatial index magic");
    }

    uint32_t version = decode_fixed32_le(data + offset);
    offset += 4;
    if (version != SpatialIndexWriter::VERSION) {
        return Status::Corruption("Unsupported spatial index version");
    }

    _cell_level = static_cast<int>(decode_fixed32_le(data + offset));
    offset += 4;

    uint32_t cell_count = decode_fixed32_le(data + offset);
    offset += 4;

    // Read cell entries
    for (uint32_t i = 0; i < cell_count; i++) {
        if (offset + 12 > file_size) {
            return Status::Corruption("Spatial index file truncated");
        }

        uint64_t cell_id = decode_fixed64_le(data + offset);
        offset += 8;

        uint32_t bitmap_size = decode_fixed32_le(data + offset);
        offset += 4;

        if (offset + bitmap_size > file_size) {
            return Status::Corruption("Spatial index bitmap data truncated");
        }

        roaring::Roaring bitmap = roaring::Roaring::readSafe(reinterpret_cast<const char*>(data + offset), bitmap_size);
        offset += bitmap_size;

        _cell_bitmaps[cell_id] = std::move(bitmap);
    }

    _loaded = true;
    return Status::OK();
}

roaring::Roaring SpatialIndexReader::search(const std::vector<uint64_t>& covering_cell_ids) const {
    roaring::Roaring result;
    for (const auto& cell_id : covering_cell_ids) {
        auto it = _cell_bitmaps.find(cell_id);
        if (it != _cell_bitmaps.end()) {
            result |= it->second;
        }
    }
    return result;
}

roaring::Roaring SpatialIndexReader::search_with_expansion(const std::vector<uint64_t>& covering_cell_ids) const {
    roaring::Roaring result;

    for (const auto& query_cell_id : covering_cell_ids) {
        // Direct match at the index level
        auto it = _cell_bitmaps.find(query_cell_id);
        if (it != _cell_bitmaps.end()) {
            result |= it->second;
        }

        int query_level = s2cell_level(query_cell_id);

        // If query cell is at a coarser level than index, check children
        if (query_level < _cell_level) {
            for (const auto& [indexed_cell_id, bitmap] : _cell_bitmaps) {
                if (s2cell_contains(query_cell_id, indexed_cell_id)) {
                    result |= bitmap;
                }
            }
        }

        // If query cell is at a finer level than index, check parent
        if (query_level > _cell_level) {
            uint64_t parent_id = s2cell_parent(query_cell_id, _cell_level);
            auto parent_it = _cell_bitmaps.find(parent_id);
            if (parent_it != _cell_bitmaps.end()) {
                result |= parent_it->second;
            }
        }
    }

    return result;
}

} // namespace starrocks
