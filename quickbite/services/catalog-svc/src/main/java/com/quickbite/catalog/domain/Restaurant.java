package com.quickbite.catalog.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Document("restaurants")
public record Restaurant(
                @Id String id,
                String name,
                String cuisine,
                @Indexed String cityId, // shard key candidate (file 12 / capacity plan)
                String area, // neighbourhood, for display
                @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE) GeoJsonPoint location, // GeoJSON order:
                                                                                                   // [lon, lat]
                double rating,
                int avgPrepMinutes,
                String imageUrl,
                List<MenuItem> menu) {
}