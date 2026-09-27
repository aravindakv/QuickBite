package com.quickbite.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun MapPane(destination: Pair<Double, Double>, rider: Pair<Double, Double>?) {
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
            map.overlays.add(Marker(map).apply { position = dest; title = "You" })
            rider?.let { map.overlays.add(Marker(map).apply { position = GeoPoint(it.first, it.second); title = "Rider" }) }
            map.controller.setCenter(rider?.let { GeoPoint(it.first, it.second) } ?: dest)
            map.invalidate()
        })
}