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

#include "storage/index/s2/s2_index_writer.h"

#include <algorithm>

#include "column/column.h"
#include "column/column_helper.h"
#include "column/column_viewer.h"
#include "fs/fs.h"
#include "storage/index/index_descriptor.h"
#include "storage/index/s2/s2_cell_ops.h"

// Arrow/Parquet headers for writing the index file
#include <arrow/api.h>
#include <arrow/io/api.h>
#include <parquet/arrow/writer.h>
#include <parquet/properties.h>

// Bridge macro: convert arrow::Status to starrocks::Status
#undef ARROW_RETURN_NOT_OK
#define ARROW_RETURN_NOT_OK(expr)                                           \
    do {                                                                    \
        auto _arrow_st = (expr);                                            \
        if (!_arrow_st.ok()) {                                              \
            return Status::IOError("Arrow error: " + _arrow_st.ToString()); \
        }                                                                   \
    } while (0)

namespace starrocks {

Status S2IndexWriter::create(const std::shared_ptr<TabletIndex>& tablet_index, const std::string& index_file_path,
                             std::unique_ptr<S2IndexWriter>* writer) {
    *writer = std::make_unique<S2IndexWriter>(tablet_index, index_file_path);
    return Status::OK();
}

S2IndexWriter::S2IndexWriter(std::shared_ptr<TabletIndex> tablet_index, std::string index_file_path)
        : _tablet_index(std::move(tablet_index)), _index_file_path(std::move(index_file_path)) {}

S2IndexWriter::S2IndexWriter(int32_t cell_level, std::string index_file_path)
        : _tablet_index(nullptr), _index_file_path(std::move(index_file_path)), _cell_level(cell_level) {}

Status S2IndexWriter::create(int32_t cell_level, const std::string& index_file_path,
                             std::unique_ptr<S2IndexWriter>* writer) {
    *writer = std::make_unique<S2IndexWriter>(cell_level, index_file_path);
    return Status::OK();
}

Status S2IndexWriter::init() {
    // For remote FS factory path, cell_level is already set via constructor
    if (_tablet_index == nullptr) {
        if (_cell_level < 1 || _cell_level > 30) {
            return Status::InvalidArgument("cell_level must be in range [1, 30]");
        }
        return Status::OK();
    }
    // Parse cell_level from index properties
    const auto& props = _tablet_index->common_properties();
    auto it = props.find("cell_level");
    if (it != props.end()) {
        try {
            _cell_level = std::stoi(it->second);
        } catch (const std::exception& e) {
            return Status::InvalidArgument("Invalid cell_level: " + it->second);
        }
        if (_cell_level < 1 || _cell_level > 30) {
            return Status::InvalidArgument("cell_level must be in range [1, 30]");
        }
    }
    return Status::OK();
}

Status S2IndexWriter::append_lat_lng(const Column& lat_col, const Column& lng_col, size_t num_rows) {
    // Get raw double data from columns
    const auto* lat_data = reinterpret_cast<const double*>(lat_col.raw_data());
    const auto* lng_data = reinterpret_cast<const double*>(lng_col.raw_data());

    _cell_row_pairs.reserve(_cell_row_pairs.size() + num_rows);
    for (size_t i = 0; i < num_rows; i++) {
        int64_t cell_id = s2_ops::lat_lng_to_cell_id(lat_data[i], lng_data[i], _cell_level);
        _cell_row_pairs.emplace_back(cell_id, _current_row_offset + static_cast<int64_t>(i));
    }
    _current_row_offset += num_rows;
    return Status::OK();
}

Status S2IndexWriter::append_wkt(const Column& geo_col, size_t num_rows) {
    // For WKT mode, we extract the centroid point from each geometry string.
    // This is a simplified approach — for polygons, we use the centroid lat/lng.
    // A full implementation would index all cells covering the geometry.
    ColumnViewer<TYPE_VARCHAR> viewer(&geo_col);

    _cell_row_pairs.reserve(_cell_row_pairs.size() + num_rows);
    for (size_t i = 0; i < num_rows; i++) {
        if (viewer.is_null(i)) {
            _current_row_offset++;
            continue;
        }
        auto value = viewer.value(i);
        std::string wkt(value.data, value.size);

        // Parse "POINT(lng lat)" format — simplest case
        // For production, we'd use a full WKT parser and index all covering cells
        double lng = 0, lat = 0;
        if (sscanf(wkt.c_str(), "POINT(%lf %lf)", &lng, &lat) == 2 ||
            sscanf(wkt.c_str(), "POINT (%lf %lf)", &lng, &lat) == 2) {
            int64_t cell_id = s2_ops::lat_lng_to_cell_id(lat, lng, _cell_level);
            _cell_row_pairs.emplace_back(cell_id, _current_row_offset + static_cast<int64_t>(i));
        }
        // Skip non-POINT geometries for now (they would need region covering)
    }
    _current_row_offset += num_rows;
    return Status::OK();
}

Status S2IndexWriter::finish(uint64_t* index_size) {
    if (_cell_row_pairs.empty()) {
        // Write empty marker file
        ASSIGN_OR_RETURN(auto fs, FileSystem::CreateSharedFromString(_index_file_path));
        WritableFileOptions wopts;
        ASSIGN_OR_RETURN(auto wfile, fs->new_writable_file(wopts, _index_file_path));
        RETURN_IF_ERROR(wfile->append(IndexDescriptor::s2_empty_mark));
        RETURN_IF_ERROR(wfile->close());
        *index_size = IndexDescriptor::s2_empty_mark_len;
        return Status::OK();
    }

    // Sort by s2_cell_id for optimal compression and range lookups
    std::sort(_cell_row_pairs.begin(), _cell_row_pairs.end(),
              [](const auto& a, const auto& b) { return a.first < b.first; });

    // Build Arrow arrays
    const size_t n = _cell_row_pairs.size();
    arrow::Int64Builder cell_id_builder;
    arrow::Int64Builder row_id_builder;
    ARROW_RETURN_NOT_OK(cell_id_builder.Reserve(n));
    ARROW_RETURN_NOT_OK(row_id_builder.Reserve(n));

    for (const auto& [cell_id, row_id] : _cell_row_pairs) {
        cell_id_builder.UnsafeAppend(cell_id);
        row_id_builder.UnsafeAppend(row_id);
    }

    std::shared_ptr<arrow::Array> cell_id_array;
    std::shared_ptr<arrow::Array> row_id_array;
    ARROW_RETURN_NOT_OK(cell_id_builder.Finish(&cell_id_array));
    ARROW_RETURN_NOT_OK(row_id_builder.Finish(&row_id_array));

    // Create Arrow table
    auto schema = arrow::schema({arrow::field("s2_cell_id", arrow::int64()), arrow::field("row_id", arrow::int64())});
    auto table = arrow::Table::Make(schema, {cell_id_array, row_id_array});

    // Configure Parquet writer properties
    auto props = ::parquet::WriterProperties::Builder()
                         .compression(arrow::Compression::ZSTD)
                         ->data_pagesize(1024 * 1024) // 1MB pages
                         ->enable_statistics()
                         ->build();

    auto arrow_props = ::parquet::ArrowWriterProperties::Builder().store_schema()->build();

    // Write to file via Arrow output stream
    auto sink_result = arrow::io::FileOutputStream::Open(_index_file_path);
    ARROW_RETURN_NOT_OK(sink_result.status());
    auto sink = std::move(sink_result).ValueUnsafe();

    // Use small row groups so that each covers a narrow cell_id range.
    // Since data is sorted by cell_id, this enables the reader to skip
    // non-overlapping row groups via min/max statistics pruning.
    static constexpr int64_t kRowGroupSize = 4096;
    ARROW_RETURN_NOT_OK(::parquet::arrow::WriteTable(*table, arrow::default_memory_pool(), sink,
                                                     /*chunk_size=*/kRowGroupSize, props, arrow_props));
    ARROW_RETURN_NOT_OK(sink->Close());

    // Get file size
    ASSIGN_OR_RETURN(auto fs, FileSystem::CreateSharedFromString(_index_file_path));
    ASSIGN_OR_RETURN(auto rfile, fs->new_random_access_file(_index_file_path));
    ASSIGN_OR_RETURN(auto file_size, rfile->get_size());
    *index_size = static_cast<uint64_t>(file_size);

    // Free buffer memory
    _cell_row_pairs.clear();
    _cell_row_pairs.shrink_to_fit();

    return Status::OK();
}

} // namespace starrocks
