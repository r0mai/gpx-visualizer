# GPS Visualizer — Android

A mountain-biking-focused companion to the web visualizer in [`../site`](../site).
Load GPX tours (local files or from Dropbox), overlay them on a tourist / MTB
map, and ride with live position tracking that auto-orients the map to your
direction of travel — with real 3D terrain relief.

## Features

- **MapLibre Native** map engine — free, no API key.
- **Tourist / MTB base maps**: OpenTopoMap, **CyclOSM** (cycling/MTB focused),
  and Esri satellite. Switch via the layers button.
- **3D tilt + terrain shading**: the **3D** button pitches the camera into an
  oblique, perspective view (great for a bar-mounted phone), and every style
  layers in **hillshade** relief from free, key-less AWS "Terrarium" DEM tiles.
  > Heads-up: MapLibre Native Android (11.x) does not yet render an *extruded*
  > 3D terrain mesh — that feature (Terrain3D) is still in development upstream.
  > So "3D" here means camera tilt + shaded relief, not raised terrain geometry.
  > `MapStyles.kt` already wires the DEM source so the extruded `terrain`
  > property can be dropped in once MapLibre Native ships it.
- **GPX overlays**: tracks, routes and waypoints, each tour in its own color,
  with per-tour show/hide and a "show N most recent" slider.
- **Ride mode**: tap the location button to follow your position. The map
  rotates to your GPS travel direction (great for a landscape bar mount) and a
  small HUD shows speed + altitude. The screen stays awake while following.
- **Dropbox**: link your account (PKCE OAuth, reusing the web app's app key) and
  sync all `.gpx` files under a folder. Also import local files via the system
  picker.

## Building

Requirements: **Android Studio** (Ladybug / 2024.2 or newer) with the bundled
JDK (17 or 21). The project targets `compileSdk` / `targetSdk` 34, `minSdk` 24.

1. Open the `android/` folder in Android Studio.
2. Let it sync (it will download Gradle 8.9, the Android SDK components, and the
   MapLibre / Dropbox dependencies).
3. Run the `app` configuration on a device or emulator.

> **Gradle wrapper jar:** this repo intentionally omits the binary
> `gradle/wrapper/gradle-wrapper.jar`. Android Studio regenerates it on first
> sync. To build from the command line instead, run `gradle wrapper` once (with
> a system Gradle ≥ 8.9) to create the jar, then use `./gradlew assembleDebug`.
> Note: the system JDK here is 25, which is newer than Gradle 8.9 supports — use
> Android Studio's embedded JDK, or install JDK 17/21 for CLI builds.

## Dropbox setup

The app reuses the same Dropbox **app key** as the web app
(`kkon8scyxqw70w9`, set in `app/build.gradle.kts`). For the native OAuth
redirect to work, the app registers the `db-kkon8scyxqw70w9` URI scheme
(see `AndroidManifest.xml`). The existing app in the
[Dropbox App Console](https://www.dropbox.com/developers/apps) already has the
required scopes enabled:

- `files.metadata.read`
- `files.content.read`

No extra console change is normally required for the native SDK flow — the app
key alone identifies the app. If you fork this with your own key, update it in
`app/build.gradle.kts` (it feeds both `BuildConfig.DROPBOX_APP_KEY` and the
manifest redirect scheme).

## Notes / tuning

- **Terrain source**: `MapStyles.TERRAIN_DEM_URL` points at the public AWS
  elevation tiles. If that endpoint is ever slow/unavailable, swap it for
  another Terrarium/DEM source (e.g. a MapTiler terrain-rgb URL with your key,
  changing `"encoding"` to `"mapbox"`).
- **3D amount**: tune the `FOLLOW_TILT_3D` / `FREE_TILT_3D` (camera pitch)
  constants in `MapController`, and `hillshadeExaggeration` in `MapStyles`.
- **Location engine**: ride tracking uses MapLibre's default (platform GPS)
  location engine, so no Google Play Services dependency is required.

## Layout

```
android/
  app/src/main/java/dev/r0mai/gpsvisualizer/
    App.kt                     # MapLibre init
    MainActivity.kt            # hosts Compose, completes Dropbox auth on resume
    gpx/                       # GPX parser + Tour model + stats
    map/                       # MapStyles (style JSON) + MapController (MapLibre bridge)
    data/                      # Dropbox, local file import, GPS HUD tracker
    ui/                        # MapViewModel, MapScreen (Compose), Theme
```
