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

package com.starrocks.common;

import com.starrocks.thrift.TS2SearchOptions;

/**
 * Carries S2 spatial index search parameters from the optimizer to the plan node.
 * Cell range computation is done on the BE side using the S2 C++ library.
 */
public class S2SearchOptions {
    private boolean enableS2Index = false;
    private int cellLevel = 15;
    private int maxCells = 8;
    // Spatial predicate parameters for BE-side cell range computation
    private String queryType = "";     // "point", "circle"
    private double queryLat = 0;
    private double queryLng = 0;
    private double queryRadiusMeters = 0;

    public boolean isEnableS2Index() {
        return enableS2Index;
    }

    public void setEnableS2Index(boolean enableS2Index) {
        this.enableS2Index = enableS2Index;
    }

    public int getCellLevel() {
        return cellLevel;
    }

    public void setCellLevel(int cellLevel) {
        this.cellLevel = cellLevel;
    }

    public int getMaxCells() {
        return maxCells;
    }

    public void setMaxCells(int maxCells) {
        this.maxCells = maxCells;
    }

    public String getQueryType() {
        return queryType;
    }

    public void setQueryType(String queryType) {
        this.queryType = queryType;
    }

    public double getQueryLat() {
        return queryLat;
    }

    public void setQueryLat(double queryLat) {
        this.queryLat = queryLat;
    }

    public double getQueryLng() {
        return queryLng;
    }

    public void setQueryLng(double queryLng) {
        this.queryLng = queryLng;
    }

    public double getQueryRadiusMeters() {
        return queryRadiusMeters;
    }

    public void setQueryRadiusMeters(double queryRadiusMeters) {
        this.queryRadiusMeters = queryRadiusMeters;
    }

    public TS2SearchOptions toThrift() {
        TS2SearchOptions opts = new TS2SearchOptions();
        opts.setEnable_s2_index(true);
        opts.setCell_level(cellLevel);
        opts.setMax_cells(maxCells);
        opts.setQuery_type(queryType);
        opts.setQuery_lat(queryLat);
        opts.setQuery_lng(queryLng);
        opts.setQuery_radius_meters(queryRadiusMeters);
        return opts;
    }
}
