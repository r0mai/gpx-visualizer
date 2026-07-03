package dev.r0mai.gpsvisualizer.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.r0mai.gpsvisualizer.gpx.Format
import dev.r0mai.gpsvisualizer.gpx.Tour
import dev.r0mai.gpsvisualizer.map.MapController
import dev.r0mai.gpsvisualizer.map.MapStyleId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

@Composable
fun MapScreen(vm: MapViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mapView = rememberMapViewWithLifecycle()
    val controller = remember(mapView) { MapController(mapView) }

    // Wire controller callbacks + first bind.
    LaunchedEffect(controller) {
        controller.onFollowCancelled = { vm.onFollowCancelledByGesture() }
        controller.bind()
    }

    // Observe state → drive the map.
    val tours by vm.tours.collectAsStateWithLifecycle()
    val styleId by vm.styleId.collectAsStateWithLifecycle()
    val is3D by vm.is3D.collectAsStateWithLifecycle()
    val following by vm.isFollowing.collectAsStateWithLifecycle()
    val hasPerm by vm.hasLocationPermission.collectAsStateWithLifecycle()
    val fitEvent by vm.fitEvent.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val rideStats by vm.rideStats.collectAsStateWithLifecycle()

    LaunchedEffect(tours) { controller.setTours(tours) }
    LaunchedEffect(styleId) { controller.setStyle(styleId) }
    LaunchedEffect(is3D) { controller.set3D(is3D) }
    LaunchedEffect(following) { controller.setFollowing(following) }
    LaunchedEffect(hasPerm) { controller.setLocationPermission(hasPerm) }
    LaunchedEffect(fitEvent) { if (fitEvent > 0) controller.fitToVisibleTours() }

    // Location permission.
    LaunchedEffect(Unit) { vm.setLocationPermission(hasLocationPermission(context)) }
    var pendingFollow by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        vm.setLocationPermission(granted)
        if (granted && pendingFollow) vm.setFollowing(true)
        pendingFollow = false
    }

    // Local file import.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.importLocalFiles(uris) }
    val openImport: () -> Unit = { importLauncher.launch(arrayOf("*/*")) }

    // Keep the screen awake while following (bike-mount use).
    val activity = context as? Activity
    DisposableEffect(following) {
        if (following) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (following) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Transient status banner. Progress text is replaced in place while loading;
    // final messages auto-dismiss shortly after loading finishes.
    LaunchedEffect(status, isLoading) {
        if (status != null && !isLoading) {
            delay(4000)
            vm.clearStatus()
        }
    }

    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                PanelContent(
                    vm = vm,
                    onImportClick = { scope.launch { drawerState.close() }; openImport() },
                    onConnectDropbox = { activity?.let { vm.connectDropbox(it) } },
                    onTourClick = { tour ->
                        controller.fitToTour(tour)
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        Scaffold(
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

                // Overlay controls (kept clear of system bars).
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    // Top-left: open panel.
                    SmallFloatingActionButton(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    ) { Icon(Icons.Filled.Menu, contentDescription = "Tours & sources") }

                    // Ride HUD.
                    if (following && rideStats != null) {
                        RideHud(
                            speedKmh = rideStats?.speedKmh,
                            altitude = rideStats?.altitude,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                        )
                    }

                    // Status banner (loading progress / results).
                    status?.let { msg ->
                        StatusBanner(
                            text = msg,
                            loading = isLoading,
                            modifier = Modifier
                                .align(if (following) Alignment.TopStart else Alignment.TopCenter)
                                .padding(top = 64.dp, start = 12.dp, end = 12.dp),
                        )
                    }

                    // Loading spinner.
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(28.dp),
                        )
                    }

                    // Right-hand action stack.
                    Column(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StyleFab(current = styleId, onSelect = { vm.setStyle(it) })

                        SmallFloatingActionButton(
                            onClick = { vm.toggle3D() },
                            containerColor = if (is3D) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface,
                        ) { Icon(Icons.Filled.Terrain, contentDescription = "Toggle 3D") }

                        SmallFloatingActionButton(
                            onClick = { controller.fitToVisibleTours() },
                        ) { Icon(Icons.Filled.FitScreen, contentDescription = "Fit all tours") }

                        SmallFloatingActionButton(
                            onClick = openImport,
                        ) { Icon(Icons.Filled.FolderOpen, contentDescription = "Import GPX") }

                        FloatingActionButton(
                            onClick = {
                                if (hasPerm) {
                                    vm.toggleFollow()
                                } else {
                                    pendingFollow = true
                                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            },
                            containerColor = if (following) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface,
                        ) {
                            Icon(
                                if (following) Icons.Filled.Navigation else Icons.Filled.MyLocation,
                                contentDescription = "Follow my location",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleFab(current: MapStyleId, onSelect: (MapStyleId) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SmallFloatingActionButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Layers, contentDescription = "Map style")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MapStyleId.entries.forEach { style ->
                DropdownMenuItem(
                    text = {
                        Text(
                            style.label,
                            fontWeight = if (style == current) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = { onSelect(style); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(text: String, loading: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f),
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
            Text(
                text,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RideHud(speedKmh: Double?, altitude: Double?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudStat(value = speedKmh?.let { "%.1f".format(it) } ?: "–", unit = "km/h")
            HudStat(value = altitude?.let { it.roundToInt().toString() } ?: "–", unit = "m")
        }
    }
}

@Composable
private fun HudStat(value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(unit, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PanelContent(
    vm: MapViewModel,
    onImportClick: () -> Unit,
    onConnectDropbox: () -> Unit,
    onTourClick: (Tour) -> Unit,
) {
    val tours by vm.tours.collectAsStateWithLifecycle()
    val dropboxLinked by vm.dropboxLinked.collectAsStateWithLifecycle()
    val dropboxFolder by vm.dropboxFolder.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        Text("GPS Visualizer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        // Dropbox
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (dropboxLinked) {
                    OutlinedTextField(
                        value = dropboxFolder,
                        onValueChange = { vm.setDropboxFolder(it) },
                        label = { Text("Dropbox folder (blank = root)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.syncDropbox() }) { Text("Sync") }
                        OutlinedButton(onClick = { vm.disconnectDropbox() }) { Text("Unlink") }
                    }
                } else {
                    Button(onClick = onConnectDropbox, modifier = Modifier.fillMaxWidth()) {
                        Text("Connect Dropbox")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onImportClick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Import GPX files")
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        val total = tours.size
        val visibleCount = tours.count { it.visible }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tours: $visibleCount / $total", fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = { vm.showAll() }) { Text("All") }
                TextButton(onClick = { vm.hideAll() }) { Text("None") }
            }
        }

        if (total > 1) {
            var sliderPos by remember(total) { mutableStateOf(total.toFloat()) }
            androidx.compose.material3.Slider(
                value = sliderPos,
                onValueChange = {
                    sliderPos = it
                    vm.showNewest(it.roundToInt())
                },
                valueRange = 0f..total.toFloat(),
                steps = (total - 1).coerceAtLeast(0),
            )
            Text(
                "Show ${sliderPos.roundToInt()} most recent",
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(tours, key = { it.id }) { tour ->
                TourRow(
                    tour = tour,
                    onToggle = { vm.toggleTour(tour.id) },
                    onClick = { onTourClick(tour) },
                )
            }
        }
    }
}

@Composable
private fun TourRow(tour: Tour, onToggle: () -> Unit, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(safeColor(tour.colorHex), CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tour.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(statsLine(tour), style = MaterialTheme.typography.labelSmall)
            }
            Checkbox(checked = tour.visible, onCheckedChange = { onToggle() })
        }
    }
}

private fun statsLine(tour: Tour): String {
    val parts = mutableListOf(Format.distance(tour.distanceKm))
    Format.duration(tour.durationMs).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    if (tour.eleGain > 0) parts.add("↗${tour.eleGain.roundToInt()}m")
    return parts.joinToString(" • ")
}

private fun safeColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Red)

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}
