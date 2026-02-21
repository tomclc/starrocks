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

#include "storage/index/spatial/spatial_index_writer.h"

#include "common/status.h"
#include "fs/fs.h"
#include "storage/index/spatial/spatial_index_utils.h"
#include "base/coding.h"

namespace starrocks {

SpatialIndexWriter::SpatialIndexWriter(int cell_level, const std::string& file_path)
        : _cell_level(cell_level), _file_path(file_path) {}

Status SpatialIndexWriter::init() {
    if (_cell_level < 1 || _cell_level > 30) {
        return Status::InvalidArgument("s2_cell_level must be in range [1, 30]");
    }
    return Status::OK();
}

Status SpatialIndexWriter::append(const double* lng_data, const double* lat_data, uint32_t num_rows,
                                  uint32_t row_offset) {
    for (uint32_t i = 0; i < num_rows; i++) {
        double lng = lng_data[i];
        double lat = lat_data[i];

        if (!SpatialIndexUtils::is_valid_lng_lat(lng, lat)) {
            continue;
        }

        uint64_t cell_id = SpatialIndexUtils::compute_cell_id(lng, lat, _cell_level);
        _cell_bitmaps[cell_id].add(row_offset + i);
    }
    _total_rows += num_rows;
    return Status::OK();
}

Status SpatialIndexWriter::finish(uint64_t* index_size) {
    ASSIGN_OR_RETURN(auto wfile, FileSystem::Default()->new_writable_file(_file_path));

    std::string header;
    put_fixed32_le(&header, MAGIC);
    put_fixed32_le(&header, VERSION);
    put_fixed32_le(&header, static_cast<uint32_t>(_cell_level));
    put_fixed32_le(&header, static_cast<uint32_t>(_cell_bitmaps.size()));
    RETURN_IF_ERROR(wfile->append(header));

    uint64_t bytes_written = header.size();

    for (auto& [cell_id, bitmap] : _cell_bitmaps) {
        bitmap.runOptimize();

        std::string entry;
        put_fixed64_le(&entry, cell_id);

        uint32_t bitmap_size = static_cast<uint32_t>(bitmap.getSizeInBytes());
        put_fixed32_le(&entry, bitmap_size);

        std::string bitmap_buf(bitmap_size, '\0');
        bitmap.write(bitmap_buf.data());
        entry.append(bitmap_buf);

        RETURN_IF_ERROR(wfile->append(entry));
        bytes_written += entry.size();
    }

    RETURN_IF_ERROR(wfile->close());

    if (index_size != nullptr) {
        *index_size = bytes_written;
    }
    return Status::OK();
}

} // namespace starrocks
