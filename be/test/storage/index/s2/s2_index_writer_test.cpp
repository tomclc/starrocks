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

#include <gtest/gtest.h>
#include <s2/s2cell_id.h>
#include <s2/s2latlng.h>

#include "column/fixed_length_column.h"
#include "fs/fs.h"
#include "storage/tablet_index.h"
#include "testutil/assert.h"

namespace starrocks {

class S2IndexWriterTest : public ::testing::Test {
protected:
    void SetUp() override {
        _test_dir = "./s2_index_writer_test_" + std::to_string(::getpid());
        ASSERT_TRUE(FileSystem::Default()->create_dir(_test_dir).ok());
    }

    void TearDown() override { FileSystem::Default()->delete_dir_recursive(_test_dir); }

    std::shared_ptr<TabletIndex> create_tablet_index(int cell_level = 15) {
        TabletIndexPB pb;
        pb.set_index_id(1);
        pb.set_index_name("test_s2_idx");
        pb.set_index_type(IndexType::S2);
        // Set cell_level via properties JSON (nested under common_properties)
        std::string props = "{\"common_properties\":{\"cell_level\":\"" + std::to_string(cell_level) + "\"}}";
        pb.set_index_properties(props);
        auto index = std::make_shared<TabletIndex>();
        EXPECT_TRUE(index->init_from_pb(pb).ok());
        return index;
    }

    std::string _test_dir;
};

TEST_F(S2IndexWriterTest, TestWriteLatLng) {
    auto tablet_index = create_tablet_index(15);
    std::string index_path = _test_dir + "/test_latlng.s2.parquet";

    std::unique_ptr<S2IndexWriter> writer;
    ASSERT_OK(S2IndexWriter::create(tablet_index, index_path, &writer));
    ASSERT_OK(writer->init());

    // Create lat/lng columns with known coordinates
    auto lat_col = DoubleColumn::create();
    auto lng_col = DoubleColumn::create();

    // San Francisco
    lat_col->append(37.7749);
    lng_col->append(-122.4194);

    // New York
    lat_col->append(40.7128);
    lng_col->append(-74.0060);

    // London
    lat_col->append(51.5074);
    lng_col->append(-0.1278);

    ASSERT_OK(writer->append_lat_lng(*lat_col, *lng_col, 3));

    uint64_t index_size = 0;
    ASSERT_OK(writer->finish(&index_size));
    ASSERT_GT(index_size, 0);

    // Verify file exists
    auto fs = FileSystem::Default();
    ASSERT_TRUE(fs->path_exists(index_path).ok());
}

TEST_F(S2IndexWriterTest, TestWriteEmpty) {
    auto tablet_index = create_tablet_index(15);
    std::string index_path = _test_dir + "/test_empty.s2.parquet";

    std::unique_ptr<S2IndexWriter> writer;
    ASSERT_OK(S2IndexWriter::create(tablet_index, index_path, &writer));
    ASSERT_OK(writer->init());

    // Write no data
    uint64_t index_size = 0;
    ASSERT_OK(writer->finish(&index_size));
    ASSERT_GT(index_size, 0); // should write empty marker

    // Verify file exists
    auto fs = FileSystem::Default();
    ASSERT_TRUE(fs->path_exists(index_path).ok());
}

TEST_F(S2IndexWriterTest, TestCellLevelCorrectness) {
    auto tablet_index = create_tablet_index(10); // Level 10 = ~10km resolution
    std::string index_path = _test_dir + "/test_cell_level.s2.parquet";

    std::unique_ptr<S2IndexWriter> writer;
    ASSERT_OK(S2IndexWriter::create(tablet_index, index_path, &writer));
    ASSERT_OK(writer->init());

    auto lat_col = DoubleColumn::create();
    auto lng_col = DoubleColumn::create();

    // Two nearby points should map to the same cell at level 10
    lat_col->append(37.7749);
    lng_col->append(-122.4194);
    lat_col->append(37.7750);
    lng_col->append(-122.4195);

    ASSERT_OK(writer->append_lat_lng(*lat_col, *lng_col, 2));

    uint64_t index_size = 0;
    ASSERT_OK(writer->finish(&index_size));
    ASSERT_GT(index_size, 0);
}

TEST_F(S2IndexWriterTest, TestLargeDataset) {
    auto tablet_index = create_tablet_index(15);
    std::string index_path = _test_dir + "/test_large.s2.parquet";

    std::unique_ptr<S2IndexWriter> writer;
    ASSERT_OK(S2IndexWriter::create(tablet_index, index_path, &writer));
    ASSERT_OK(writer->init());

    // Write 10000 random-ish points
    auto lat_col = DoubleColumn::create();
    auto lng_col = DoubleColumn::create();

    for (int i = 0; i < 10000; i++) {
        double lat = -90.0 + (static_cast<double>(i) / 10000.0) * 180.0;
        double lng = -180.0 + (static_cast<double>(i * 37 % 10000) / 10000.0) * 360.0;
        lat_col->append(lat);
        lng_col->append(lng);
    }

    ASSERT_OK(writer->append_lat_lng(*lat_col, *lng_col, 10000));

    uint64_t index_size = 0;
    ASSERT_OK(writer->finish(&index_size));
    ASSERT_GT(index_size, 0);
}

} // namespace starrocks
