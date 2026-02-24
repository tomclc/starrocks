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

// Forward declarations for Arrow/Parquet
namespace parquet {
class ParquetFileReader;
} // namespace parquet

namespace starrocks {
class FileSystem;
} // namespace starrocks

namespace roaring {
class Roaring;
} // namespace roaring

namespace starrocks {

// Represents a range of S2 cell IDs [min_cell_id, max_cell_id].
// Uses int64_t to match Parquet's INT64 storage and statistics ordering.
struct S2CellIdRange {
    int64_t min_cell_id;
    int64_t max_cell_id;
};

// S2IndexReader opens a Parquet sidecar index file and supports
// cell range lookups to produce a row ID bitmap for spatial pruning.
class S2IndexReader {
public:
    static Status open(const std::string& index_file_path, std::shared_ptr<S2IndexReader>* reader);

    // Overload for remote filesystems (HDFS/S3) — bridges StarRocks FileSystem to Arrow
    static Status open(const std::string& index_file_path, FileSystem* fs,
                       std::shared_ptr<S2IndexReader>* reader);

    ~S2IndexReader() = default;

    // Given a set of S2CellId ranges, return matching row IDs as a roaring bitmap
    Status get_row_ranges(const std::vector<S2CellIdRange>& cell_ranges, roaring::Roaring* row_bitmap);

    bool is_empty() const { return _is_empty; }

private:
    S2IndexReader() = default;

    std::string _index_file_path;
    bool _is_empty = true;

    // Parquet reader for the index file
    std::unique_ptr<::parquet::ParquetFileReader> _parquet_reader;
};

} // namespace starrocks
