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

#include <gtest/gtest.h>
#include <s2/s2cell_id.h>
#include <s2/s2latlng.h>

#include "column/fixed_length_column.h"
#include "fs/fs.h"
#include "roaring/roaring.hh"
#include "storage/index/s2/s2_index_writer.h"
#include "storage/tablet_index.h"
#include "testutil/assert.h"

namespace starrocks {

class S2IndexReaderTest : public ::testing::Test {
protected:
    void SetUp() override {
        _test_dir = "./s2_index_reader_test_" + std::to_string(::getpid());
        ASSERT_TRUE(FileSystem::Default()->create_dir(_test_dir).ok());
    }

    void TearDown() override { FileSystem::Default()->delete_dir_recursive(_test_dir); }

    std::shared_ptr<TabletIndex> create_tablet_index(int cell_level = 15) {
        TabletIndexPB pb;
        pb.set_index_id(1);
        pb.set_index_name("test_s2_idx");
        pb.set_index_type(IndexType::S2);
        std::string props = "{\"cell_level\":\"" + std::to_string(cell_level) + "\"}";
        pb.set_index_properties(props);
        auto index = std::make_shared<TabletIndex>();
        index->init_from_pb(pb);
        return index;
    }

    // Helper: write an index with known lat/lng points and return the file path
    std::string write_test_index(int cell_level, const std::vector<double>& lats, const std::vector<double>& lngs) {
        auto tablet_index = create_tablet_index(cell_level);
        std::string index_path = _test_dir + "/test_read_" + std::to_string(_file_counter++) + ".s2.parquet";

        std::unique_ptr<S2IndexWriter> writer;
        EXPECT_TRUE(S2IndexWriter::create(tablet_index, index_path, &writer).ok());
        EXPECT_TRUE(writer->init().ok());

        auto lat_col = DoubleColumn::create();
        auto lng_col = DoubleColumn::create();
        for (size_t i = 0; i < lats.size(); i++) {
            lat_col->append(lats[i]);
            lng_col->append(lngs[i]);
        }
        EXPECT_TRUE(writer->append_lat_lng(*lat_col, *lng_col, lats.size()).ok());

        uint64_t index_size = 0;
        EXPECT_TRUE(writer->finish(&index_size).ok());
        return index_path;
    }

    // Helper: compute cell ID for a lat/lng at given level
    static uint64_t cell_id_for(double lat, double lng, int level) {
        return S2CellId(S2LatLng::FromDegrees(lat, lng)).parent(level).id();
    }

    std::string _test_dir;
    int _file_counter = 0;
};

TEST_F(S2IndexReaderTest, TestOpenAndReadBasic) {
    int cell_level = 15;
    // Three well-known cities
    std::vector<double> lats = {37.7749, 40.7128, 51.5074};
    std::vector<double> lngs = {-122.4194, -74.0060, -0.1278};

    std::string index_path = write_test_index(cell_level, lats, lngs);

    std::shared_ptr<S2IndexReader> reader;
    ASSERT_OK(S2IndexReader::open(index_path, &reader));
    ASSERT_FALSE(reader->is_empty());

    // Query for San Francisco's cell range
    uint64_t sf_cell = cell_id_for(37.7749, -122.4194, cell_level);
    S2CellIdRange range{sf_cell, sf_cell};

    roaring::Roaring bitmap;
    ASSERT_OK(reader->get_row_ranges({range}, &bitmap));

    // Should find at least one row (the SF row)
    ASSERT_GT(bitmap.cardinality(), 0);
}

TEST_F(S2IndexReaderTest, TestEmptyIndex) {
    auto tablet_index = create_tablet_index(15);
    std::string index_path = _test_dir + "/test_empty.s2.parquet";

    std::unique_ptr<S2IndexWriter> writer;
    ASSERT_OK(S2IndexWriter::create(tablet_index, index_path, &writer));
    ASSERT_OK(writer->init());

    uint64_t index_size = 0;
    ASSERT_OK(writer->finish(&index_size));

    // Open reader on empty index
    std::shared_ptr<S2IndexReader> reader;
    ASSERT_OK(S2IndexReader::open(index_path, &reader));
    ASSERT_TRUE(reader->is_empty());

    // Query should return empty bitmap
    S2CellIdRange range{0, UINT64_MAX};
    roaring::Roaring bitmap;
    ASSERT_OK(reader->get_row_ranges({range}, &bitmap));
    ASSERT_EQ(bitmap.cardinality(), 0);
}

TEST_F(S2IndexReaderTest, TestQueryNoMatch) {
    int cell_level = 15;
    // Only San Francisco
    std::vector<double> lats = {37.7749};
    std::vector<double> lngs = {-122.4194};

    std::string index_path = write_test_index(cell_level, lats, lngs);

    std::shared_ptr<S2IndexReader> reader;
    ASSERT_OK(S2IndexReader::open(index_path, &reader));

    // Query for a cell range that is far from San Francisco (e.g. Tokyo area)
    uint64_t tokyo_cell = cell_id_for(35.6762, 139.6503, cell_level);
    S2CellIdRange range{tokyo_cell, tokyo_cell};

    roaring::Roaring bitmap;
    ASSERT_OK(reader->get_row_ranges({range}, &bitmap));

    // Should find no matching rows
    ASSERT_EQ(bitmap.cardinality(), 0);
}

TEST_F(S2IndexReaderTest, TestMultipleCellRanges) {
    int cell_level = 15;
    // Three cities
    std::vector<double> lats = {37.7749, 40.7128, 51.5074};
    std::vector<double> lngs = {-122.4194, -74.0060, -0.1278};

    std::string index_path = write_test_index(cell_level, lats, lngs);

    std::shared_ptr<S2IndexReader> reader;
    ASSERT_OK(S2IndexReader::open(index_path, &reader));

    // Query for both SF and London
    uint64_t sf_cell = cell_id_for(37.7749, -122.4194, cell_level);
    uint64_t london_cell = cell_id_for(51.5074, -0.1278, cell_level);

    std::vector<S2CellIdRange> ranges;
    ranges.push_back({sf_cell, sf_cell});
    ranges.push_back({london_cell, london_cell});

    roaring::Roaring bitmap;
    ASSERT_OK(reader->get_row_ranges(ranges, &bitmap));

    // Should find at least 2 rows (SF and London)
    ASSERT_GE(bitmap.cardinality(), 2);
}

TEST_F(S2IndexReaderTest, TestWideCellRange) {
    int cell_level = 15;
    // 100 points spread across latitudes
    std::vector<double> lats;
    std::vector<double> lngs;
    for (int i = 0; i < 100; i++) {
        lats.push_back(-90.0 + (static_cast<double>(i) / 100.0) * 180.0);
        lngs.push_back(0.0);
    }

    std::string index_path = write_test_index(cell_level, lats, lngs);

    std::shared_ptr<S2IndexReader> reader;
    ASSERT_OK(S2IndexReader::open(index_path, &reader));

    // Query with a very wide range (should match everything)
    S2CellIdRange wide_range{0, UINT64_MAX};
    roaring::Roaring bitmap;
    ASSERT_OK(reader->get_row_ranges({wide_range}, &bitmap));

    // Should find all 100 rows
    ASSERT_EQ(bitmap.cardinality(), 100);
}

TEST_F(S2IndexReaderTest, TestDifferentCellLevels) {
    // Level 10 = ~10km resolution
    int cell_level = 10;
    // Two nearby points that should be in the same cell at level 10
    std::vector<double> lats = {37.7749, 37.7750};
    std::vector<double> lngs = {-122.4194, -122.4195};

    std::string index_path = write_test_index(cell_level, lats, lngs);

    std::shared_ptr<S2IndexReader> reader;
    ASSERT_OK(S2IndexReader::open(index_path, &reader));

    // Query for the cell of the first point
    uint64_t cell = cell_id_for(37.7749, -122.4194, cell_level);
    S2CellIdRange range{cell, cell};

    roaring::Roaring bitmap;
    ASSERT_OK(reader->get_row_ranges({range}, &bitmap));

    // Both points should be in the same cell at level 10, so both rows match
    ASSERT_EQ(bitmap.cardinality(), 2);
}

TEST_F(S2IndexReaderTest, TestLargeDatasetRead) {
    int cell_level = 15;
    std::vector<double> lats;
    std::vector<double> lngs;
    for (int i = 0; i < 10000; i++) {
        lats.push_back(-90.0 + (static_cast<double>(i) / 10000.0) * 180.0);
        lngs.push_back(-180.0 + (static_cast<double>(i * 37 % 10000) / 10000.0) * 360.0);
    }

    std::string index_path = write_test_index(cell_level, lats, lngs);

    std::shared_ptr<S2IndexReader> reader;
    ASSERT_OK(S2IndexReader::open(index_path, &reader));
    ASSERT_FALSE(reader->is_empty());

    // Query for a specific known point
    uint64_t cell = cell_id_for(lats[5000], lngs[5000], cell_level);
    S2CellIdRange range{cell, cell};

    roaring::Roaring bitmap;
    ASSERT_OK(reader->get_row_ranges({range}, &bitmap));

    // Should find at least the one row
    ASSERT_GE(bitmap.cardinality(), 1);
}

} // namespace starrocks
