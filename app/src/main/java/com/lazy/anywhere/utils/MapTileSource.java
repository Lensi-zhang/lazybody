package com.lazy.anywhere.utils;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

public class MapTileSource {
    public static final OnlineTileSourceBase GAODE_ROAD = new OnlineTileSourceBase(
            "GaodeRoad",
            1, 18, 256, "",
            new String[]{
                    "https://wprd01.is.autonavi.com/appmaptile?",
                    "https://wprd02.is.autonavi.com/appmaptile?"
            }
    ) {
        @Override
        public String getTileURLString(long pMapTileIndex) {
            int zoom = MapTileIndex.getZoom(pMapTileIndex);
            int x = MapTileIndex.getX(pMapTileIndex);
            int y = MapTileIndex.getY(pMapTileIndex);
            return getBaseUrl() + "x=" + x + "&y=" + y + "&z=" + zoom + "&lang=zh_cn&size=1&scl=1&style=7";
        }
    };

    public static final OnlineTileSourceBase GAODE_SATELLITE = new OnlineTileSourceBase(
            "GaodeSatellite",
            1, 18, 256, "",
            new String[]{
                    "https://webst01.is.autonavi.com/appmaptile?",
                    "https://webst02.is.autonavi.com/appmaptile?"
            }
    ) {
        @Override
        public String getTileURLString(long pMapTileIndex) {
            int zoom = MapTileIndex.getZoom(pMapTileIndex);
            int x = MapTileIndex.getX(pMapTileIndex);
            int y = MapTileIndex.getY(pMapTileIndex);
            return getBaseUrl() + "style=6&x=" + x + "&y=" + y + "&z=" + zoom;
        }
    };

    public static final OnlineTileSourceBase TENCENT_ROAD = new OnlineTileSourceBase(
            "TencentRoad",
            1, 18, 256, "",
            new String[]{
                    "https://rt0.map.gtimg.com/realtimerender?",
                    "https://rt1.map.gtimg.com/realtimerender?"
            }
    ) {
        @Override
        public String getTileURLString(long pMapTileIndex) {
            int zoom = MapTileIndex.getZoom(pMapTileIndex);
            int x = MapTileIndex.getX(pMapTileIndex);
            int y = MapTileIndex.getY(pMapTileIndex);
            return getBaseUrl() + "z=" + zoom + "&x=" + x + "&y=" + y + "&type=vector&style=0";
        }
    };
}
