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

import com.starrocks.thrift.TSpatialSearchOptions;

public class SpatialSearchOptions {

    private boolean enableSpatialIndex = false;
    private String queryWkt = "";
    private double centerLng = 0.0;
    private double centerLat = 0.0;
    private double radiusMeters = 0.0;
    private String predicateType = "";
    private long knnK = 0;
    // Runtime spatial filter for joins
    private boolean runtimeSpatialFilter = false;
    private java.util.List<String> runtimeWktRegions = new java.util.ArrayList<>();

    public boolean isEnableSpatialIndex() {
        return enableSpatialIndex;
    }

    public void setEnableSpatialIndex(boolean enableSpatialIndex) {
        this.enableSpatialIndex = enableSpatialIndex;
    }

    public String getQueryWkt() {
        return queryWkt;
    }

    public void setQueryWkt(String queryWkt) {
        this.queryWkt = queryWkt;
    }

    public double getCenterLng() {
        return centerLng;
    }

    public void setCenterLng(double centerLng) {
        this.centerLng = centerLng;
    }

    public double getCenterLat() {
        return centerLat;
    }

    public void setCenterLat(double centerLat) {
        this.centerLat = centerLat;
    }

    public double getRadiusMeters() {
        return radiusMeters;
    }

    public void setRadiusMeters(double radiusMeters) {
        this.radiusMeters = radiusMeters;
    }

    public String getPredicateType() {
        return predicateType;
    }

    public void setPredicateType(String predicateType) {
        this.predicateType = predicateType;
    }

    public long getKnnK() {
        return knnK;
    }

    public void setKnnK(long knnK) {
        this.knnK = knnK;
    }

    public boolean isRuntimeSpatialFilter() {
        return runtimeSpatialFilter;
    }

    public void setRuntimeSpatialFilter(boolean runtimeSpatialFilter) {
        this.runtimeSpatialFilter = runtimeSpatialFilter;
    }

    public java.util.List<String> getRuntimeWktRegions() {
        return runtimeWktRegions;
    }

    public void setRuntimeWktRegions(java.util.List<String> runtimeWktRegions) {
        this.runtimeWktRegions = runtimeWktRegions;
    }

    public TSpatialSearchOptions toThrift() {
        TSpatialSearchOptions opts = new TSpatialSearchOptions();
        opts.setEnable_spatial_index(enableSpatialIndex);
        opts.setQuery_wkt(queryWkt);
        opts.setCenter_lng(centerLng);
        opts.setCenter_lat(centerLat);
        opts.setRadius_meters(radiusMeters);
        opts.setPredicate_type(predicateType);
        opts.setKnn_k(knnK);
        opts.setIs_runtime_spatial_filter(runtimeSpatialFilter);
        if (runtimeWktRegions != null && !runtimeWktRegions.isEmpty()) {
            opts.setRuntime_wkt_regions(runtimeWktRegions);
        }
        return opts;
    }

    public String getExplainString(String prefix) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append("SPATIALINDEX: ON\n");
        sb.append(prefix).append("  Predicate Type: ").append(predicateType);
        if (runtimeSpatialFilter) {
            sb.append(" [RUNTIME FILTER]");
        }
        if ("contains".equals(predicateType)) {
            if (!runtimeSpatialFilter && !queryWkt.isEmpty()) {
                sb.append(", Query WKT: ").append(queryWkt);
            }
        } else if ("distance".equals(predicateType)) {
            sb.append(", Center: (").append(centerLng).append(", ").append(centerLat).append(")");
            sb.append(", Radius: ").append(radiusMeters).append("m");
        } else if ("knn".equals(predicateType)) {
            sb.append(", Center: (").append(centerLng).append(", ").append(centerLat).append(")");
            sb.append(", K: ").append(knnK);
        }
        if (runtimeSpatialFilter && runtimeWktRegions != null && !runtimeWktRegions.isEmpty()) {
            sb.append(", Regions: ").append(runtimeWktRegions.size());
        }
        sb.append("\n");
        return sb.toString();
    }
}
