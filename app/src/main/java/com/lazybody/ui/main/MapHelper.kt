package com.lazybody.ui.main

import android.content.Context
import com.lazybody.utils.MapTileSource
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

class MapHelper(private val context: Context) {

    private var mapView: MapView? = null
    private var currentMarker: Marker? = null

    fun initialize(mapView: MapView) {
        this.mapView = mapView

        Configuration.getInstance().load(
            context,
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        )
        Configuration.getInstance().userAgentValue = context.packageName

        mapView.setTileSource(MapTileSource.GAODE_ROAD)
        mapView.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        mapView.setMultiTouchControls(true)
    }

    fun setNightModeFilter() {
        mapView?.let { map ->
            val nightMatrix = floatArrayOf(
                -1.0f, 0f, 0f, 0f, 255f,
                0f, -1.0f, 0f, 0f, 255f,
                0f, 0f, -1.0f, 0f, 255f,
                0f, 0f, 0f, 1.0f, 0f
            )
            map.overlayManager.tilesOverlay.setColorFilter(
                android.graphics.ColorMatrixColorFilter(nightMatrix)
            )
        }
    }

    fun setMapClickListener(onClick: (GeoPoint) -> Unit) {
        mapView?.let { map ->
            val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    onClick(p)
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            })
            map.overlays.add(0, eventsOverlay)
        }
    }

    fun markPosition(geoPoint: GeoPoint, drawableId: Int) {
        mapView?.let { map ->
            currentMarker?.let { map.overlays.remove(it) }

            currentMarker = Marker(map).apply {
                position = geoPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = androidx.core.content.ContextCompat.getDrawable(context, drawableId)
                title = "Selected Location"
            }
            map.overlays.add(currentMarker)
            map.invalidate()
        }
    }

    fun clearMarker() {
        mapView?.let { map ->
            currentMarker?.let { map.overlays.remove(it) }
            currentMarker = null
            map.invalidate()
        }
    }

    fun setCenter(geoPoint: GeoPoint) {
        mapView?.controller?.setCenter(geoPoint)
    }

    fun animateTo(geoPoint: GeoPoint) {
        mapView?.controller?.animateTo(geoPoint)
    }

    fun setZoom(zoom: Double) {
        mapView?.controller?.setZoom(zoom)
    }

    fun zoomIn() {
        mapView?.controller?.zoomIn()
    }

    fun zoomOut() {
        mapView?.controller?.zoomOut()
    }
}
