package dev.r0mai.gpsvisualizer.map

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import dev.r0mai.gpsvisualizer.gpx.Bounds
import dev.r0mai.gpsvisualizer.gpx.ROUTE_COLOR_HEX
import dev.r0mai.gpsvisualizer.gpx.Tour
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentConstants
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Bridges app state to a MapLibre [MapView]: applies base styles, draws GPX
 * tour overlays as GeoJSON line/circle layers, toggles 3D via camera pitch, and
 * drives the location component for follow + auto-orient-to-travel-direction.
 *
 * All mutating methods are safe to call before the map/style are ready; the
 * latest desired state is stashed and applied once loading completes.
 */
class MapController(private val mapView: MapView) {

    private val context: Context = mapView.context

    private var map: MapLibreMap? = null
    private var style: Style? = null

    // Desired state
    private var styleId = MapStyleId.OPENTOPO
    private var pitch3d = false
    private var following = false
    private var locationEnabled = false
    private var hasLocationPermission = false
    private var tours: List<Tour> = emptyList()

    // Bookkeeping
    private val addedSourceIds = mutableSetOf<String>()
    private val addedLayerIds = mutableListOf<String>()
    private var locationActivated = false
    private var pendingFit = false

    // System-bar insets (px) so the top-right compass clears the transparent
    // status bar / navigation buttons.
    private var compassTopMarginPx = 0
    private var compassRightMarginPx = 0

    /** Called when a user gesture breaks follow mode so the UI can update its toggle. */
    var onFollowCancelled: (() -> Unit)? = null

    private val followTilt: Double get() = if (pitch3d) FOLLOW_TILT_3D else 0.0

    fun bind(onReady: (() -> Unit)? = null) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = true
                isCompassEnabled = true
                isAttributionEnabled = true
                isLogoEnabled = true
                // Keep the logo + attribution at the bottom-center so they stay
                // clear of the lower-left ride HUD (portrait) and the lower-right
                // control stack.
                val bottomCenter = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setLogoGravity(bottomCenter)
                setAttributionGravity(bottomCenter)
            }
            applyCompassMargins()
            m.setMinZoomPreference(2.0)
            m.setMaxZoomPreference(20.0)
            m.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(46.8, 11.0)) // Alps – a sensible MTB default view
                        .zoom(5.0)
                        .build(),
                ),
            )

            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && following) {
                    following = false
                    onFollowCancelled?.invoke()
                }
            }

            applyStyle(onReady)
        }
    }

    // ---- Public state setters -------------------------------------------------

    fun setStyle(id: MapStyleId) {
        val changed = id != styleId
        styleId = id
        // On first bind the style is loaded by bind(); only reload on real changes.
        if (map != null && changed) applyStyle(null)
    }

    fun set3D(enabled: Boolean) {
        if (enabled == pitch3d) return
        pitch3d = enabled
        val lc = locationComponentOrNull()
        if (following && lc != null) {
            lc.tiltWhileTracking(followTilt)
        } else {
            applyPitch(animate = true)
        }
    }

    fun setFollowing(enabled: Boolean) {
        following = enabled && hasLocationPermission
        applyFollowCamera()
        if (!enabled) applyPitch(animate = true)
    }

    /** Show/hide the position marker (and its GPS engine) independent of follow. */
    fun setLocationEnabled(enabled: Boolean) {
        locationEnabled = enabled && hasLocationPermission
        applyFollowCamera()
    }

    fun setTours(newTours: List<Tour>) {
        val idsChanged = newTours.map { it.id } != tours.map { it.id }
        tours = newTours
        if (style == null) return
        if (idsChanged) rebuildTourLayers() else applyVisibility()
    }

    fun setLocationPermission(granted: Boolean) {
        hasLocationPermission = granted
        if (granted && style != null && !locationActivated) {
            setupLocationComponent()
        }
    }

    /** Offset the top-right compass by the system-bar insets (in px). */
    fun setCompassMargins(topPx: Int, rightPx: Int) {
        compassTopMarginPx = topPx
        compassRightMarginPx = rightPx
        applyCompassMargins()
    }

    private fun applyCompassMargins() {
        val m = map ?: return
        m.uiSettings.setCompassMargins(0, compassTopMarginPx, compassRightMarginPx, 0)
    }

    fun fitToVisibleTours() {
        if (map == null || style == null) {
            pendingFit = true
            return
        }
        fitToVisibleInternal(animate = true)
    }

    /** Frame a single tour (from a tour-list tap). Also cancels follow mode. */
    fun fitToTour(tour: Tour) {
        val m = map ?: return
        val b = tour.bounds ?: return
        following = false
        applyFollowCamera()
        onFollowCancelled?.invoke()
        try {
            val update = if (b.north - b.south < 1e-4 && b.east - b.west < 1e-4) {
                CameraUpdateFactory.newLatLngZoom(LatLng(b.south, b.west), 14.0)
            } else {
                CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.from(b.north, b.east, b.south, b.west),
                    FIT_PADDING_PX,
                )
            }
            m.animateCamera(update, 800)
        } catch (_: Exception) {
            // ignore – map not laid out
        }
    }

    // ---- Style + overlays -----------------------------------------------------

    private fun applyStyle(then: (() -> Unit)?) {
        val m = map ?: return
        // A style reload discards all sources/layers and detaches the location component.
        addedSourceIds.clear()
        addedLayerIds.clear()
        locationActivated = false

        m.setStyle(Style.Builder().fromJson(MapStyles.json(styleId))) { loaded ->
            style = loaded
            rebuildTourLayers()
            setupLocationComponent()
            applyFollowCamera()
            if (!following) applyPitch(animate = false)
            if (pendingFit) {
                pendingFit = false
                fitToVisibleInternal(animate = false)
            }
            then?.invoke()
        }
    }

    private fun rebuildTourLayers() {
        val s = style ?: return
        addedLayerIds.forEach { s.getLayer(it)?.let { l -> s.removeLayer(l) } }
        addedSourceIds.forEach { s.getSource(it)?.let { src -> s.removeSource(src) } }
        addedLayerIds.clear()
        addedSourceIds.clear()

        // Keep tour overlays beneath the location puck so the position marker
        // always draws on top of the red route/waypoint markers. Once the
        // location component is activated its layers live in the style; anchor
        // tour layers below the bottom-most one (s.layers is bottom-to-top).
        // Before activation there is no anchor, so layers append on top.
        val locationAnchor = s.layers.firstOrNull { it.id in LOCATION_LAYER_IDS }?.id
        val addTourLayer: (Layer) -> Unit = { layer ->
            if (locationAnchor != null) s.addLayerBelow(layer, locationAnchor) else s.addLayer(layer)
        }

        tours.forEachIndexed { index, tour ->
            val key = "tour-$index"
            val vis = if (tour.visible) Property.VISIBLE else Property.NONE

            val lineFeatures = tour.lines
                .filter { it.size > 1 }
                .map { line ->
                    Feature.fromGeometry(
                        LineString.fromLngLats(line.map { Point.fromLngLat(it.lon, it.lat) }),
                    )
                }
            if (lineFeatures.isNotEmpty()) {
                val srcId = "$key-line-src"
                s.addSource(GeoJsonSource(srcId, FeatureCollection.fromFeatures(lineFeatures)))
                addedSourceIds.add(srcId)

                // Single solid red line, no casing/outline: a "coverage" mask.
                val lineId = "$key-line"
                addTourLayer(
                    LineLayer(lineId, srcId).withProperties(
                        lineColor(ROUTE_COLOR_HEX),
                        lineWidth(ROUTE_WIDTH),
                        lineOpacity(1.0f),
                        lineCap(Property.LINE_CAP_ROUND),
                        lineJoin(Property.LINE_JOIN_ROUND),
                        visibility(vis),
                    ),
                )
                addedLayerIds.add(lineId)
            }

            if (tour.waypoints.isNotEmpty()) {
                val wSrc = "$key-wpt-src"
                val wptFeatures = tour.waypoints.map {
                    Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))
                }
                s.addSource(GeoJsonSource(wSrc, FeatureCollection.fromFeatures(wptFeatures)))
                addedSourceIds.add(wSrc)

                val wLayer = "$key-wpt"
                addTourLayer(
                    CircleLayer(wLayer, wSrc).withProperties(
                        circleColor(ROUTE_COLOR_HEX),
                        circleRadius(4.5f),
                        circleStrokeColor("#ffffff"),
                        circleStrokeWidth(1.5f),
                        visibility(vis),
                    ),
                )
                addedLayerIds.add(wLayer)
            }
        }
    }

    private fun applyVisibility() {
        val s = style ?: return
        tours.forEachIndexed { index, tour ->
            val vis = if (tour.visible) Property.VISIBLE else Property.NONE
            listOf("tour-$index-line", "tour-$index-wpt").forEach { id ->
                s.getLayer(id)?.setProperties(visibility(vis))
            }
        }
    }

    // ---- Camera ---------------------------------------------------------------

    private fun applyPitch(animate: Boolean) {
        val m = map ?: return
        if (following) return
        val target = if (pitch3d) FREE_TILT_3D else 0.0
        val pos = CameraPosition.Builder(m.cameraPosition).tilt(target).build()
        val update = CameraUpdateFactory.newCameraPosition(pos)
        if (animate) m.animateCamera(update, 500) else m.moveCamera(update)
    }

    private fun applyFollowCamera() {
        val lc = locationComponentOrNull() ?: return
        // The location marker (and thus the GPS radio + its per-frame rendering)
        // runs while location is enabled, so the position marker stays visible
        // even after a pan cancels follow. It is disabled — saving battery when
        // just browsing — once the user turns location off via the follow FAB.
        // Only camera tracking is tied to `following`.
        lc.isLocationComponentEnabled = locationEnabled
        if (following) {
            // GPS render mode orients the puck from the GPS course, so no
            // magnetometer/compass sensor is used.
            lc.renderMode = RenderMode.GPS
            lc.cameraMode = CameraMode.TRACKING_GPS // follows position + rotates to travel direction
            lc.zoomWhileTracking(FOLLOW_ZOOM)
            lc.tiltWhileTracking(followTilt)
        } else {
            lc.cameraMode = CameraMode.NONE
            lc.renderMode = RenderMode.NORMAL
        }
    }

    private fun fitToVisibleInternal(animate: Boolean) {
        val m = map ?: return
        val boundsList = tours.filter { it.visible && it.bounds != null }.map { it.bounds!! }
        val u = Bounds.union(boundsList) ?: return

        try {
            val update = if (u.north - u.south < 1e-4 && u.east - u.west < 1e-4) {
                CameraUpdateFactory.newLatLngZoom(LatLng(u.south, u.west), 14.0)
            } else {
                val llb = LatLngBounds.from(u.north, u.east, u.south, u.west)
                CameraUpdateFactory.newLatLngBounds(llb, FIT_PADDING_PX)
            }
            if (animate) m.animateCamera(update, 800) else m.moveCamera(update)
        } catch (_: Exception) {
            // Map not laid out yet; retry once it is.
            pendingFit = true
        }
    }

    // ---- Location component ---------------------------------------------------

    private fun locationComponentOrNull() =
        if (locationActivated) map?.locationComponent else null

    @SuppressLint("MissingPermission")
    private fun setupLocationComponent() {
        val m = map ?: return
        val s = style ?: return
        if (!hasLocationPermission || locationActivated) return

        // No pulse animation: it would render every frame forever and never let
        // the GPU idle. The puck is drawn statically and updates on demand.
        // A reduced icon scale keeps the position marker small and unobtrusive.
        val componentOptions = LocationComponentOptions.builder(context)
            .minZoomIconScale(LOCATION_ICON_SCALE)
            .maxZoomIconScale(LOCATION_ICON_SCALE)
            .build()
        val options = LocationComponentActivationOptions.builder(context, s)
            .locationComponentOptions(componentOptions)
            .useDefaultLocationEngine(true)
            .build()

        m.locationComponent.activateLocationComponent(options)
        locationActivated = true
        // applyFollowCamera enables the component + GPS only when following.
        applyFollowCamera()
    }

    companion object {
        private const val ROUTE_WIDTH = 5.0f
        private const val FOLLOW_ZOOM = 16.0
        private const val FOLLOW_TILT_3D = 55.0
        private const val FREE_TILT_3D = 55.0
        private const val FIT_PADDING_PX = 90

        // Shrink the default location puck (default max scale is 1.0).
        private const val LOCATION_ICON_SCALE = 0.6f

        // Layer ids the location component registers in the style; used to keep
        // tour overlays anchored beneath the position marker.
        private val LOCATION_LAYER_IDS = setOf(
            LocationComponentConstants.SHADOW_LAYER,
            LocationComponentConstants.BACKGROUND_LAYER,
            LocationComponentConstants.FOREGROUND_LAYER,
            LocationComponentConstants.BEARING_LAYER,
            LocationComponentConstants.ACCURACY_LAYER,
            LocationComponentConstants.PULSING_CIRCLE_LAYER,
        )
    }
}
