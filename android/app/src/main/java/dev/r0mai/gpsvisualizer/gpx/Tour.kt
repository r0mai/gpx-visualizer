package dev.r0mai.gpsvisualizer.gpx

import kotlin.math.abs

/** A single GPS point. [ele] is meters, [timeMs] is epoch millis; both optional. */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val timeMs: Long? = null,
)

/** Geographic bounding box of a tour. */
data class Bounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
) {
    companion object {
        fun of(points: List<GeoPoint>): Bounds? {
            if (points.isEmpty()) return null
            var n = -90.0
            var s = 90.0
            var e = -180.0
            var w = 180.0
            for (p in points) {
                if (p.lat > n) n = p.lat
                if (p.lat < s) s = p.lat
                if (p.lon > e) e = p.lon
                if (p.lon < w) w = p.lon
            }
            return Bounds(n, s, e, w)
        }

        fun union(list: List<Bounds>): Bounds? {
            if (list.isEmpty()) return null
            return Bounds(
                north = list.maxOf { it.north },
                south = list.minOf { it.south },
                east = list.maxOf { it.east },
                west = list.minOf { it.west },
            )
        }
    }
}

data class Waypoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val name: String = "",
    val description: String = "",
)

/**
 * A parsed tour. [lines] is a flattened list of polylines (one per track
 * segment and one per route) ready to render; [waypoints] are standalone points.
 */
data class Tour(
    val id: String,
    val filename: String,
    val name: String,
    val description: String,
    val lines: List<List<GeoPoint>>,
    val waypoints: List<Waypoint>,
    val bounds: Bounds?,
    val distanceKm: Double,
    val eleMin: Double?,
    val eleMax: Double?,
    val eleGain: Double,
    val eleLoss: Double,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val colorHex: String,
    val visible: Boolean = true,
) {
    val durationMs: Long?
        get() = if (startTimeMs != null && endTimeMs != null) endTimeMs - startTimeMs else null
}

object Format {
    fun distance(km: Double): String =
        if (km < 1.0) "${(km * 1000).toInt()} m" else "%.1f km".format(km)

    fun duration(ms: Long?): String {
        if (ms == null || ms <= 0) return ""
        val totalMin = ms / 60000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun elevation(m: Double?): String = if (m == null) "–" else "${m.toInt()} m"
}

/** Palette used to color successive tours; cycles when exhausted. */
object TourPalette {
    val colors = listOf(
        "#E74C3C", "#3498DB", "#2ECC71", "#F39C12", "#9B59B6", "#1ABC9C",
        "#E67E22", "#E84393", "#00B894", "#0984E3", "#6C5CE7", "#D63031",
        "#FDCB6E", "#00CEC9", "#B2BEC3", "#636E72",
    )

    fun at(index: Int): String = colors[((index % colors.size) + colors.size) % colors.size]
}

/** Haversine distance in kilometers. */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/** Compute distance / elevation / time stats over an ordered list of points. */
class TourStats {
    var distanceKm = 0.0
    var eleMin: Double? = null
    var eleMax: Double? = null
    var eleGain = 0.0
    var eleLoss = 0.0
    var startTimeMs: Long? = null
    var endTimeMs: Long? = null

    fun addLine(points: List<GeoPoint>) {
        for (i in points.indices) {
            val p = points[i]
            if (i > 0) {
                val prev = points[i - 1]
                distanceKm += haversineKm(prev.lat, prev.lon, p.lat, p.lon)
                if (prev.ele != null && p.ele != null) {
                    val d = p.ele - prev.ele
                    if (d > 0) eleGain += d else eleLoss += abs(d)
                }
            }
            p.ele?.let { e ->
                eleMin = minOf(eleMin ?: e, e)
                eleMax = maxOf(eleMax ?: e, e)
            }
            p.timeMs?.let { t ->
                startTimeMs = minOf(startTimeMs ?: t, t)
                endTimeMs = maxOf(endTimeMs ?: t, t)
            }
        }
    }
}
