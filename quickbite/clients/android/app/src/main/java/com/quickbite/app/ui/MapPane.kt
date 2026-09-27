package com.quickbite.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun MapPane(
    destination: Pair<Double, Double>,
    rider: Pair<Double, Double>?,
    path: List<Pair<Double, Double>> = emptyList(),
) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(280.dp),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            val dest = GeoPoint(destination.first, destination.second)

            if (path.size >= 2) {                                   // the route travelled so far
                map.overlays.add(Polyline(map).apply {
                    setPoints(path.map { GeoPoint(it.first, it.second) })
                    outlinePaint.strokeWidth = 10f
                    outlinePaint.color = 0xFF1E88E5.toInt()
                })
            }
            rider?.let { r ->                                       // dashed "as the crow flies" leg to the door
                map.overlays.add(Polyline(map).apply {
                    setPoints(listOf(GeoPoint(r.first, r.second), dest))
                    outlinePaint.strokeWidth = 5f
                    outlinePaint.color = 0x661E88E5
                })
                map.overlays.add(Marker(map).apply { position = GeoPoint(r.first, r.second); title = "Rider" })
            }
            map.overlays.add(Marker(map).apply { position = dest; title = "You" })

            // Frame rider + path + destination instead of centring on one point
            val all = (path.map { GeoPoint(it.first, it.second) } + listOfNotNull(rider?.let { GeoPoint(it.first, it.second) }) + dest)
            map.post {
                if (all.size >= 2) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all).increaseByScale(1.4f), true, 60)
                else map.controller.setCenter(dest)
            }
            map.invalidate()
        })
}