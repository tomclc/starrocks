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

#include "storage/index/s2/s2_index_reader.h"

#include <algorithm>
#include <parquet/api/reader.h>
#include <parquet/statistics.h>

#include "fs/fs.h"
#include "roaring/roaring.hh"
#include "storage/index/index_descriptor.h"

// Arrow IO headers for remote FS bridge
#include <arrow/io/interfaces.h>

namespace starrocks {

// Bridge: wraps a StarRocks RandomAccessFile as an Arrow RandomAccessFile.
// Enables S2IndexReader to open sidecar files on remote filesystems (HDFS/S3).
class ArrowRandomAccessFileBridge : public arrow::io::RandomAccessFile {
public:
    explicit ArrowRandomAccessFileBridge(std::shared_ptr<starrocks::RandomAccessFile> file, int64_t file_size)
            : _file(std::move(file)), _file_size(file_size), _pos(0) {}

    ~ArrowRandomAccessFileBridge() override = default;

    arrow::Status Close() override {
        _file.reset();
        return arrow::Status::OK();
    }
    bool closed() const override { return _file == nullptr; }

    arrow::Result<int64_t> Tell() const override { return _pos; }

    arrow::Result<int64_t> GetSize() override { return _file_size; }

    arrow::Status Seek(int64_t position) override {
        _pos = position;
        return arrow::Status::OK();
    }

    arrow::Result<int64_t> Read(int64_t nbytes, void* buffer) override {
        auto st = _file->read_at_fully(_pos, buffer, nbytes);
        if (!st.ok()) {
            return arrow::Status::IOError(st.message());
        }
        _pos += nbytes;
        return nbytes;
    }

    arrow::Result<std::shared_ptr<arrow::Buffer>> Read(int64_t nbytes) override {
        ARROW_ASSIGN_OR_RAISE(auto buf, arrow::AllocateResizableBuffer(nbytes));
        ARROW_ASSIGN_OR_RAISE(int64_t bytes_read, Read(nbytes, buf->mutable_data()));
        ARROW_RETURN_NOT_OK(buf->Resize(bytes_read));
        return std::move(buf);
    }

    arrow::Result<int64_t> ReadAt(int64_t position, int64_t nbytes, void* out) override {
        auto st = _file->read_at_fully(position, out, nbytes);
        if (!st.ok()) {
            return arrow::Status::IOError(st.message());
        }
        return nbytes;
    }

    arrow::Result<std::shared_ptr<arrow::Buffer>> ReadAt(int64_t position, int64_t nbytes) override {
        ARROW_ASSIGN_OR_RAISE(auto buf, arrow::AllocateResizableBuffer(nbytes));
        ARROW_ASSIGN_OR_RAISE(int64_t bytes_read, ReadAt(position, nbytes, buf->mutable_data()));
        ARROW_RETURN_NOT_OK(buf->Resize(bytes_read));
        return std::move(buf);
    }

private:
    std::shared_ptr<starrocks::RandomAccessFile> _file;
    int64_t _file_size;
    int64_t _pos;
};

Status S2IndexReader::open(const std::string& index_file_path, FileSystem* fs,
                           std::shared_ptr<S2IndexReader>* reader) {
    auto r = std::shared_ptr<S2IndexReader>(new S2IndexReader());
    r->_index_file_path = index_file_path;

    ASSIGN_OR_RETURN(auto rfile, fs->new_random_access_file(index_file_path));
    ASSIGN_OR_RETURN(auto file_size, rfile->get_size());

    if (file_size <= IndexDescriptor::s2_empty_mark_len) {
        r->_is_empty = true;
        *reader = std::move(r);
        return Status::OK();
    }

    try {
        auto arrow_file = std::make_shared<ArrowRandomAccessFileBridge>(std::move(rfile), file_size);
        r->_parquet_reader = ::parquet::ParquetFileReader::Open(arrow_file);
    } catch (const std::exception& e) {
        return Status::IOError("Failed to open S2 index file via remote FS: " + std::string(e.what()));
    }

    r->_is_empty = false;
    *reader = std::move(r);
    return Status::OK();
}

Status S2IndexReader::open(const std::string& index_file_path, std::shared_ptr<S2IndexReader>* reader) {
    auto r = std::shared_ptr<S2IndexReader>(new S2IndexReader());
    r->_index_file_path = index_file_path;

    // Check if this is an empty marker file
    ASSIGN_OR_RETURN(auto fs, FileSystem::CreateSharedFromString(index_file_path));
    ASSIGN_OR_RETURN(auto rfile, fs->new_random_access_file(index_file_path));
    ASSIGN_OR_RETURN(auto file_size, rfile->get_size());

    if (file_size <= IndexDescriptor::s2_empty_mark_len) {
        r->_is_empty = true;
        *reader = std::move(r);
        return Status::OK();
    }

    // Open as Parquet file
    try {
        r->_parquet_reader = ::parquet::ParquetFileReader::OpenFile(index_file_path, /*memory_map=*/false);
    } catch (const std::exception& e) {
        return Status::IOError("Failed to open S2 index file: " + std::string(e.what()));
    }

    r->_is_empty = false;
    *reader = std::move(r);
    return Status::OK();
}

Status S2IndexReader::get_row_ranges(const std::vector<S2CellIdRange>& cell_ranges, roaring::Roaring* row_bitmap) {
    if (_is_empty || _parquet_reader == nullptr) {
        return Status::OK();
    }

    // Sort ranges by min_cell_id for binary search
    std::vector<S2CellIdRange> sorted_ranges(cell_ranges.begin(), cell_ranges.end());
    std::sort(sorted_ranges.begin(), sorted_ranges.end(),
              [](const S2CellIdRange& a, const S2CellIdRange& b) { return a.min_cell_id < b.min_cell_id; });

    auto file_metadata = _parquet_reader->metadata();
    int num_row_groups = file_metadata->num_row_groups();

    for (int rg = 0; rg < num_row_groups; rg++) {
        auto rg_metadata = file_metadata->RowGroup(rg);
        auto col_metadata = rg_metadata->ColumnChunk(0); // s2_cell_id column

        // Check row group statistics for s2_cell_id
        if (col_metadata->is_stats_set()) {
            auto stats = col_metadata->statistics();
            auto typed_stats = std::dynamic_pointer_cast<::parquet::TypedStatistics<::parquet::Int64Type>>(stats);
            if (typed_stats && typed_stats->HasMinMax()) {
                int64_t rg_min = typed_stats->min();
                int64_t rg_max = typed_stats->max();

                // Check if any query range overlaps with this row group.
                // Use binary search: find first range whose max_cell_id >= rg_min
                auto it = std::lower_bound(
                        sorted_ranges.begin(), sorted_ranges.end(), rg_min,
                        [](const S2CellIdRange& range, int64_t id) { return range.max_cell_id < id; });
                bool has_overlap = false;
                // Check this and subsequent ranges that could overlap
                for (; it != sorted_ranges.end() && it->min_cell_id <= rg_max; ++it) {
                    has_overlap = true;
                    break;
                }
                if (!has_overlap) {
                    continue; // Skip this row group entirely
                }
            }
        }

        // Read matching rows from this row group
        auto rg_reader = _parquet_reader->RowGroup(rg);
        auto cell_col_reader = rg_reader->Column(0); // s2_cell_id
        auto row_col_reader = rg_reader->Column(1);  // row_id

        auto cell_int64_reader = std::dynamic_pointer_cast<::parquet::Int64Reader>(cell_col_reader);
        auto row_int64_reader = std::dynamic_pointer_cast<::parquet::Int64Reader>(row_col_reader);

        if (!cell_int64_reader || !row_int64_reader) {
            return Status::Corruption("S2 index file has unexpected column types");
        }

        // Read in batches
        constexpr int64_t kBatchSize = 4096;
        std::vector<int64_t> cell_ids(kBatchSize);
        std::vector<int64_t> row_ids(kBatchSize);

        while (cell_int64_reader->HasNext() && row_int64_reader->HasNext()) {
            int64_t cell_values_read = 0;
            int64_t row_values_read = 0;

            cell_int64_reader->ReadBatch(kBatchSize, nullptr, nullptr, cell_ids.data(), &cell_values_read);
            row_int64_reader->ReadBatch(kBatchSize, nullptr, nullptr, row_ids.data(), &row_values_read);

            int64_t batch_size = std::min(cell_values_read, row_values_read);

            for (int64_t i = 0; i < batch_size; i++) {
                int64_t cell_id = cell_ids[i];

                // Binary search: find first range where max_cell_id >= cell_id
                auto it = std::lower_bound(
                        sorted_ranges.begin(), sorted_ranges.end(), cell_id,
                        [](const S2CellIdRange& range, int64_t id) { return range.max_cell_id < id; });
                if (it != sorted_ranges.end() && cell_id >= it->min_cell_id) {
                    row_bitmap->add(static_cast<uint32_t>(row_ids[i]));
                }
            }
        }
    }

    return Status::OK();
}

} // namespace starrocks
