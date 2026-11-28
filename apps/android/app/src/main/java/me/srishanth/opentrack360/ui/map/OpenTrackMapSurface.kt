package me.srishanth.opentrack360.ui.map

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** WGS84 coordinate in decimal degrees. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

data class MapMarker(
    val point: GeoPoint,
    val stale: Boolean = false,
)

data class MapCircle(
    val center: GeoPoint,
    val radiusMeters: Double,
) {
    init {
        require(radiusMeters.isFinite() && radiusMeters > 0.0)
    }
}

/**
 * Shared MapLibre Native map surface.
 *
 * Pass markers and circles to render them. Set [selectedPoint] for the current destination pin and
 * provide [onPointSelected] to enable map taps for choosing a destination. The surface never asks
 * for device location. Its fixed OpenFreeMap style needs no app key. Attribution and the tile-area
 * privacy/no-SLA notice remain visible over the map at all times.
 */
@Composable
fun OpenTrackMapSurface(
    modifier: Modifier = Modifier,
    markers: List<MapMarker> = emptyList(),
    circles: List<MapCircle> = emptyList(),
    selectedPoint: GeoPoint? = null,
    onPointSelected: ((GeoPoint) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPointSelected = rememberUpdatedState(onPointSelected)
    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var style by remember(mapView) { mutableStateOf<Style?>(null) }
    var initialCameraSet by remember(mapView) { mutableStateOf(false) }
    var previousSelectedPoint by remember(mapView) { mutableStateOf<GeoPoint?>(null) }

    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { _ ->
                mapView.also { view ->
                    view.getMapAsync { readyMap ->
                    map = readyMap
                    readyMap.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
                        .zoom(DEFAULT_ZOOM)
                        .build()
                    readyMap.addOnMapClickListener { coordinate ->
                        currentOnPointSelected.value?.invoke(GeoPoint(coordinate.latitude, coordinate.longitude))
                        currentOnPointSelected.value != null
                    }
                    readyMap.setStyle(STYLE_URL) { loadedStyle ->
                        style = loadedStyle
                    }
                }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        LaunchedEffect(style, markers, circles, selectedPoint) {
            val loadedStyle = style ?: return@LaunchedEffect
            ensureOverlayLayers(loadedStyle)
            updateSource(loadedStyle, FRESH_MARKERS_SOURCE, pointFeatures(markers.filterNot { it.stale }))
            updateSource(loadedStyle, STALE_MARKERS_SOURCE, pointFeatures(markers.filter { it.stale }))
            updateSource(loadedStyle, SELECTED_POINT_SOURCE, pointFeatures(selectedPoint))
            updateSource(loadedStyle, ARRIVAL_AREA_SOURCE, circleFeatures(circles))
        }

        LaunchedEffect(map, style, markers, circles, selectedPoint) {
            val readyMap = map ?: return@LaunchedEffect
            if (style == null) return@LaunchedEffect
            val firstPoint = selectedPoint
                ?: markers.firstOrNull()?.point
                ?: circles.firstOrNull()?.center
            if (!initialCameraSet) {
                firstPoint?.let {
                    readyMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), DETAIL_ZOOM),
                    )
                }
                if (firstPoint != null) initialCameraSet = true
            }
            if (selectedPoint != null && selectedPoint != previousSelectedPoint) {
                readyMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(selectedPoint.latitude, selectedPoint.longitude),
                        DETAIL_ZOOM,
                    ),
                )
            }
            previousSelectedPoint = selectedPoint
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0xE60B1220))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = "© OpenStreetMap contributors · © OpenMapTiles · OpenFreeMap",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
            Text(
                text = "Map requests reveal the viewed area to OpenFreeMap. Public map service has no SLA.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFD7DFEA),
            )
        }
    }
}

private fun ensureOverlayLayers(style: Style) {
    listOf(FRESH_MARKERS_SOURCE, STALE_MARKERS_SOURCE, SELECTED_POINT_SOURCE, ARRIVAL_AREA_SOURCE).forEach { id ->
        if (style.getSourceAs<GeoJsonSource>(id) == null) {
            style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyArray())))
        }
    }
    if (style.getLayer(ARRIVAL_AREA_FILL_LAYER) == null) {
        style.addLayer(
            FillLayer(ARRIVAL_AREA_FILL_LAYER, ARRIVAL_AREA_SOURCE)
                .withProperties(fillColor(ARRIVAL_AREA_COLOR), fillOpacity(ARRIVAL_AREA_OPACITY)),
        )
    }
    if (style.getLayer(ARRIVAL_AREA_LINE_LAYER) == null) {
        style.addLayer(
            LineLayer(ARRIVAL_AREA_LINE_LAYER, ARRIVAL_AREA_SOURCE)
                .withProperties(lineColor(ARRIVAL_AREA_COLOR), lineWidth(ARRIVAL_AREA_LINE_WIDTH)),
        )
    }
    if (style.getLayer(FRESH_MARKERS_LAYER) == null) {
        style.addLayer(
            CircleLayer(FRESH_MARKERS_LAYER, FRESH_MARKERS_SOURCE)
                .withProperties(
                    circleRadius(FRESH_MARKER_RADIUS),
                    circleColor(FRESH_MARKER_COLOR),
                    circleStrokeColor(MARKER_STROKE_COLOR),
                    circleStrokeWidth(MARKER_STROKE_WIDTH),
                ),
        )
    }
    if (style.getLayer(STALE_MARKERS_LAYER) == null) {
        style.addLayer(
            CircleLayer(STALE_MARKERS_LAYER, STALE_MARKERS_SOURCE)
                .withProperties(
                    circleRadius(FRESH_MARKER_RADIUS),
                    circleColor(STALE_MARKER_COLOR),
                    circleStrokeColor(MARKER_STROKE_COLOR),
                    circleStrokeWidth(MARKER_STROKE_WIDTH),
                ),
        )
    }
    if (style.getLayer(SELECTED_POINT_LAYER) == null) {
        style.addLayer(
            CircleLayer(SELECTED_POINT_LAYER, SELECTED_POINT_SOURCE)
                .withProperties(
                    circleRadius(SELECTED_POINT_RADIUS),
                    circleColor(SELECTED_POINT_COLOR),
                    circleStrokeColor(MARKER_STROKE_COLOR),
                    circleStrokeWidth(MARKER_STROKE_WIDTH),
                ),
        )
    }
}

private fun updateSource(style: Style, sourceId: String, collection: FeatureCollection) {
    val source = style.getSourceAs<GeoJsonSource>(sourceId)
    if (source == null) {
        style.addSource(GeoJsonSource(sourceId, collection))
    } else {
        source.setGeoJson(collection)
    }
}

private fun pointFeatures(markers: List<MapMarker>): FeatureCollection = FeatureCollection.fromFeatures(
    markers.map { Feature.fromGeometry(Point.fromLngLat(it.point.longitude, it.point.latitude)) }.toTypedArray(),
)

private fun pointFeatures(point: GeoPoint?): FeatureCollection = FeatureCollection.fromFeatures(
    point?.let {
        arrayOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))
    } ?: emptyArray(),
)

private fun circleFeatures(circles: List<MapCircle>): FeatureCollection = FeatureCollection.fromFeatures(
    circles.map { circle ->
        val centerLatitude = Math.toRadians(circle.center.latitude)
        val centerLongitude = Math.toRadians(circle.center.longitude)
        val angularDistance = circle.radiusMeters / EARTH_RADIUS_METERS
        val openRing = (0 until CIRCLE_SEGMENTS).map { index ->
            val bearing = 2.0 * Math.PI * index / CIRCLE_SEGMENTS
            val latitude = Math.asin(
                Math.sin(centerLatitude) * Math.cos(angularDistance) +
                    Math.cos(centerLatitude) * Math.sin(angularDistance) * Math.cos(bearing),
            )
            val longitude = centerLongitude + Math.atan2(
                Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(centerLatitude),
                Math.cos(angularDistance) - Math.sin(centerLatitude) * Math.sin(latitude),
            )
            Point.fromLngLat(Math.toDegrees(longitude), Math.toDegrees(latitude))
        }
        val ring = openRing + openRing.first()
        Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
    }.toTypedArray(),
)

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val FRESH_MARKERS_SOURCE = "opentrack-fresh-markers-source"
private const val STALE_MARKERS_SOURCE = "opentrack-stale-markers-source"
private const val SELECTED_POINT_SOURCE = "opentrack-selected-point-source"
private const val ARRIVAL_AREA_SOURCE = "opentrack-arrival-area-source"
private const val FRESH_MARKERS_LAYER = "opentrack-fresh-markers-layer"
private const val STALE_MARKERS_LAYER = "opentrack-stale-markers-layer"
private const val SELECTED_POINT_LAYER = "opentrack-selected-point-layer"
private const val ARRIVAL_AREA_FILL_LAYER = "opentrack-arrival-area-fill-layer"
private const val ARRIVAL_AREA_LINE_LAYER = "opentrack-arrival-area-line-layer"
private const val FRESH_MARKER_COLOR = "#2563EB"
private const val STALE_MARKER_COLOR = "#64748B"
private const val SELECTED_POINT_COLOR = "#F97316"
private const val MARKER_STROKE_COLOR = "#FFFFFF"
private const val ARRIVAL_AREA_COLOR = "#2563EB"
private const val ARRIVAL_AREA_OPACITY = 0.2f
private const val ARRIVAL_AREA_LINE_WIDTH = 2f
private const val MARKER_STROKE_WIDTH = 2f
private const val FRESH_MARKER_RADIUS = 9f
private const val SELECTED_POINT_RADIUS = 10f
private const val DEFAULT_LATITUDE = 19.0760
private const val DEFAULT_LONGITUDE = 72.8777
private const val DEFAULT_ZOOM = 10.5
private const val DETAIL_ZOOM = 14.0
private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val CIRCLE_SEGMENTS = 64
