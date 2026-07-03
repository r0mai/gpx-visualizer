package dev.r0mai.gpsvisualizer.map

/**
 * Base map styles are built as MapLibre style JSON: a raster tourist/MTB tile
 * layer plus a `hillshade` layer fed by a free, key-less AWS "Terrarium" DEM
 * for shaded relief.
 *
 * Note on "3D": MapLibre Native Android (11.x) does not yet render an extruded
 * 3D terrain mesh (Terrain3D is still in development upstream), so the top-level
 * style `terrain` property would be ignored. We therefore convey depth two ways
 * that DO work today: camera pitch (an oblique, perspective "3D tilt" — see
 * MapController) and hillshade relief. If/when Native ships terrain, add a
 * `"terrain": { "source": "terrain-dem", "exaggeration": … }` property here.
 */
enum class MapStyleId(val label: String) {
    OPENTOPO("OpenTopoMap"),
    CYCLOSM("CyclOSM (MTB)"),
    SATELLITE("Satellite"),
}

object MapStyles {

    /** Free, no-key global elevation tiles (Terrarium encoding). Powers 3D + hillshade. */
    private const val TERRAIN_DEM_URL =
        "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    fun json(id: MapStyleId): String {
        val (baseSource, attribution) = when (id) {
            MapStyleId.OPENTOPO -> rasterSource(
                tiles = listOf(
                    "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
                    "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
                    "https://c.tile.opentopomap.org/{z}/{x}/{y}.png",
                ),
                maxZoom = 17,
            ) to "© OpenStreetMap contributors, SRTM · © OpenTopoMap (CC-BY-SA)"

            MapStyleId.CYCLOSM -> rasterSource(
                tiles = listOf(
                    "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
                    "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
                    "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
                ),
                maxZoom = 20,
            ) to "© OpenStreetMap contributors · CyclOSM"

            MapStyleId.SATELLITE -> rasterSource(
                tiles = listOf(
                    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
                ),
                maxZoom = 19,
            ) to "Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics"
        }

        // Hillshade is subtle over the already-shaded topo/cyclo maps but adds
        // useful relief cues over satellite imagery.
        val hillshadeExaggeration = if (id == MapStyleId.SATELLITE) 0.45 else 0.2

        return """
        {
          "version": 8,
          "name": "${id.label}",
          "sources": {
            "base": ${baseSource.replace("__ATTR__", escape(attribution))},
            "terrain-dem": {
              "type": "raster-dem",
              "tiles": ["$TERRAIN_DEM_URL"],
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 15,
              "encoding": "terrarium"
            }
          },
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "#e8e8e8" } },
            { "id": "base", "type": "raster", "source": "base",
              "paint": { "raster-fade-duration": 200 } },
            { "id": "hillshade", "type": "hillshade", "source": "terrain-dem",
              "paint": { "hillshade-exaggeration": $hillshadeExaggeration,
                         "hillshade-shadow-color": "#5a5a5a" } }
          ]
        }
        """.trimIndent()
    }

    private fun rasterSource(tiles: List<String>, maxZoom: Int): String {
        val tileList = tiles.joinToString(",") { "\"${it}\"" }
        return """
        {
          "type": "raster",
          "tiles": [$tileList],
          "tileSize": 256,
          "minzoom": 0,
          "maxzoom": $maxZoom,
          "attribution": "__ATTR__"
        }
        """.trimIndent()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
