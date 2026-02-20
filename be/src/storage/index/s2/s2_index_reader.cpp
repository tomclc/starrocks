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

#include <parquet/api/reader.h>
#include <parquet/statistics.h>

#include "fs/fs.h"
#include "roaring/roaring.hh"
#include "storage/index/index_descriptor.h"

namespace starrocks {

S2IndexReader::~S2IndexReader() = default;

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
        r->_parquet_reader = parquet::ParquetFileReader::OpenFile(index_file_path, /*memory_map=*/false);
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

    auto file_metadata = _parquet_reader->metadata();
    int num_row_groups = file_metadata->num_row_groups();

    for (int rg = 0; rg < num_row_groups; rg++) {
        auto rg_metadata = file_metadata->RowGroup(rg);
        auto col_metadata = rg_metadata->ColumnChunk(0); // s2_cell_id column

        // Check row group statistics for s2_cell_id
        if (col_metadata->is_stats_set()) {
            auto stats = col_metadata->statistics();
            auto typed_stats = std::dynamic_pointer_cast<parquet::TypedStatistics<parquet::Int64Type>>(stats);
            if (typed_stats && typed_stats->HasMinMax()) {
                uint64_t rg_min = static_cast<uint64_t>(typed_stats->min());
                uint64_t rg_max = static_cast<uint64_t>(typed_stats->max());

                // Check if any query range overlaps with this row group
                bool has_overlap = false;
                for (const auto& range : cell_ranges) {
                    if (range.min_cell_id <= rg_max && range.max_cell_id >= rg_min) {
                        has_overlap = true;
                        break;
                    }
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

        auto cell_int64_reader = std::dynamic_pointer_cast<parquet::Int64Reader>(cell_col_reader);
        auto row_int64_reader = std::dynamic_pointer_cast<parquet::Int64Reader>(row_col_reader);

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
                uint64_t cell_id = static_cast<uint64_t>(cell_ids[i]);

                // Check if this cell_id falls within any query range
                for (const auto& range : cell_ranges) {
                    if (cell_id >= range.min_cell_id && cell_id <= range.max_cell_id) {
                        row_bitmap->add(static_cast<uint32_t>(row_ids[i]));
                        break;
                    }
                }
            }
        }
    }

    return Status::OK();
}

} // namespace starrocks
