package dev.r0mai.gpsvisualizer

import android.app.Application
import org.maplibre.android.MapLibre

/**
 * MapLibre must be initialized once, before any [org.maplibre.android.maps.MapView]
 * is inflated. We use our own self-hosted raster styles, so no API key is needed.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
