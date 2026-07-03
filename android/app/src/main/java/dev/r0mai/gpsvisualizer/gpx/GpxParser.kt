package dev.r0mai.gpsvisualizer.gpx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Streaming GPX parser mirroring the web app's parser (site/gpx-parser.js):
 * extracts tracks, routes and waypoints, then computes distance / elevation /
 * time statistics and a bounding box.
 */
object GpxParser {

    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private class PointBuilder(val lat: Double, val lon: Double) {
        var ele: Double? = null
        var timeMs: Long? = null
        fun build() = GeoPoint(lat, lon, ele, timeMs)
    }

    private class WptBuilder(val lat: Double, val lon: Double) {
        var ele: Double? = null
        var name: String = ""
        var desc: String = ""
        fun build() = Waypoint(lat, lon, ele, name, desc)
    }

    fun parse(content: String, filename: String, colorHex: String): Tour {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(content))

            val lines = mutableListOf<MutableList<GeoPoint>>()
            val waypoints = mutableListOf<Waypoint>()

            var currentLine: MutableList<GeoPoint>? = null
            var curPoint: PointBuilder? = null
            var curWpt: WptBuilder? = null

            var metadataName: String? = null
            var trkName: String? = null
            var rteName: String? = null
            var metadataDesc: String? = null
            var trkDesc: String? = null
            var rteDesc: String? = null

            val stack = ArrayDeque<String>()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase()
                        when (tag) {
                            "trkseg", "rte" -> currentLine = mutableListOf()
                            "trkpt", "rtept" -> {
                                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                curPoint = if (lat != null && lon != null) PointBuilder(lat, lon) else null
                            }
                            "wpt" -> {
                                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                curWpt = if (lat != null && lon != null) WptBuilder(lat, lon) else null
                            }
                        }
                        stack.addLast(tag)
                    }

                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            val top = stack.lastOrNull()
                            val parent = if (stack.size >= 2) stack[stack.size - 2] else null
                            when (top) {
                                "ele" -> {
                                    val e = text.toDoubleOrNull()
                                    curPoint?.let { it.ele = e }
                                    curWpt?.let { it.ele = e }
                                }
                                "time" -> curPoint?.let { it.timeMs = parseTime(text) }
                                "name" -> when (parent) {
                                    "metadata" -> if (metadataName == null) metadataName = text
                                    "trk" -> if (trkName == null) trkName = text
                                    "rte" -> if (rteName == null) rteName = text
                                    "wpt" -> curWpt?.let { it.name = text }
                                }
                                "desc" -> when (parent) {
                                    "metadata" -> if (metadataDesc == null) metadataDesc = text
                                    "trk" -> if (trkDesc == null) trkDesc = text
                                    "rte" -> if (rteDesc == null) rteDesc = text
                                    "wpt" -> curWpt?.let { it.desc = text }
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        when (parser.name.lowercase()) {
                            "trkpt", "rtept" -> {
                                curPoint?.let { currentLine?.add(it.build()) }
                                curPoint = null
                            }
                            "trkseg", "rte" -> {
                                currentLine?.let { if (it.size > 1) lines.add(it) }
                                currentLine = null
                            }
                            "wpt" -> {
                                curWpt?.let { waypoints.add(it.build()) }
                                curWpt = null
                            }
                        }
                        if (stack.isNotEmpty()) stack.removeLast()
                    }
                }
                event = parser.next()
            }

            val stats = TourStats()
            lines.forEach { stats.addLine(it) }

            val allPoints = lines.flatten()
            val bounds = Bounds.of(allPoints)

            val name = metadataName ?: trkName ?: rteName
                ?: filename.substringBeforeLast('.')
            val description = metadataDesc ?: trkDesc ?: rteDesc ?: ""

            return Tour(
                id = filename,
                filename = filename,
                name = name,
                description = description,
                lines = lines,
                waypoints = waypoints,
                bounds = bounds,
                distanceKm = stats.distanceKm,
                eleMin = stats.eleMin,
                eleMax = stats.eleMax,
                eleGain = stats.eleGain,
                eleLoss = stats.eleLoss,
                startTimeMs = stats.startTimeMs,
                endTimeMs = stats.endTimeMs,
                colorHex = colorHex,
            )
        } catch (e: Exception) {
            throw ParseException("Invalid GPX file: ${e.message}", e)
        }
    }

    private fun parseTime(s: String): Long? {
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.parse(s).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}
