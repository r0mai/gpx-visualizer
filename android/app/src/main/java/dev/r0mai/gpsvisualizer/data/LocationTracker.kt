package dev.r0mai.gpsvisualizer.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lightweight speed / altitude readout for the on-screen HUD while riding. */
data class RideStats(
    val speedKmh: Double?,
    val altitude: Double?,
    val bearing: Float?,
)

/**
 * Reads GPS updates for the HUD. This is independent of MapLibre's location
 * component (which drives the map marker + camera); both share the same GPS fix.
 */
class LocationTracker(context: Context) : LocationListener {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _stats = MutableStateFlow<RideStats?>(null)
    val stats: StateFlow<RideStats?> = _stats

    private var active = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (active) return
        active = true
        runCatching {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        }
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { manager.removeUpdates(this) }
        _stats.value = null
    }

    override fun onLocationChanged(location: Location) {
        _stats.value = RideStats(
            speedKmh = if (location.hasSpeed()) location.speed * 3.6 else null,
            altitude = if (location.hasAltitude()) location.altitude else null,
            bearing = if (location.hasBearing()) location.bearing else null,
        )
    }

    @Deprecated("Required by older LocationListener")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
