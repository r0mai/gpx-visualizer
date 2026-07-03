package dev.r0mai.gpsvisualizer.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.r0mai.gpsvisualizer.data.DropboxRepository
import dev.r0mai.gpsvisualizer.data.FileImport
import dev.r0mai.gpsvisualizer.data.GpxLoadError
import dev.r0mai.gpsvisualizer.data.LoadedGpx
import dev.r0mai.gpsvisualizer.data.LocationTracker
import dev.r0mai.gpsvisualizer.data.RideStats
import dev.r0mai.gpsvisualizer.data.SyncProgress
import dev.r0mai.gpsvisualizer.gpx.GpxParser
import dev.r0mai.gpsvisualizer.gpx.Tour
import dev.r0mai.gpsvisualizer.gpx.TourPalette
import dev.r0mai.gpsvisualizer.map.MapStyleId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapViewModel(app: Application) : AndroidViewModel(app) {

    private val dropbox = DropboxRepository(app)
    private val locationTracker = LocationTracker(app)

    // Ordered newest-first; colors assigned at load time and travel with the tour.
    private var rawTours: List<Tour> = emptyList()
    private var visibleIds: Set<String> = emptySet()
    private var colorSeed = 0

    private val _tours = MutableStateFlow<List<Tour>>(emptyList())
    val tours: StateFlow<List<Tour>> = _tours.asStateFlow()

    private val _styleId = MutableStateFlow(MapStyleId.OPENTOPO)
    val styleId: StateFlow<MapStyleId> = _styleId.asStateFlow()

    private val _is3D = MutableStateFlow(false)
    val is3D: StateFlow<Boolean> = _is3D.asStateFlow()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private val _hasLocationPermission = MutableStateFlow(false)
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()

    private val _dropboxLinked = MutableStateFlow(dropbox.isLinked)
    val dropboxLinked: StateFlow<Boolean> = _dropboxLinked.asStateFlow()

    private val _dropboxFolder = MutableStateFlow(dropbox.folderPath)
    val dropboxFolder: StateFlow<String> = _dropboxFolder.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Non-null while a Dropbox sync / local import is running.
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    private val _fitEvent = MutableStateFlow(0)
    val fitEvent: StateFlow<Int> = _fitEvent.asStateFlow()

    val rideStats: StateFlow<RideStats?> = locationTracker.stats

    // ---- Map controls ---------------------------------------------------------

    fun setStyle(id: MapStyleId) { _styleId.value = id }

    fun toggle3D() { _is3D.value = !_is3D.value }

    fun toggleFollow() { _isFollowing.value = !_isFollowing.value }

    fun setFollowing(on: Boolean) {
        _isFollowing.value = on
    }

    fun onFollowCancelledByGesture() {
        _isFollowing.value = false
    }

    fun setLocationPermission(granted: Boolean) {
        _hasLocationPermission.value = granted
        if (!granted) setFollowing(false)
    }

    // React to follow changes for the HUD tracker.
    private fun updateTracker() {
        if (_isFollowing.value && _hasLocationPermission.value) locationTracker.start()
        else locationTracker.stop()
    }

    // ---- Tour visibility ------------------------------------------------------

    fun toggleTour(id: String) {
        visibleIds = if (id in visibleIds) visibleIds - id else visibleIds + id
        publishTours()
    }

    fun showNewest(count: Int) {
        visibleIds = rawTours.take(count.coerceIn(0, rawTours.size)).map { it.id }.toSet()
        publishTours()
    }

    fun showAll() {
        visibleIds = rawTours.map { it.id }.toSet()
        publishTours()
    }

    fun hideAll() {
        visibleIds = emptySet()
        publishTours()
    }

    fun removeAll() {
        rawTours = emptyList()
        visibleIds = emptySet()
        publishTours()
    }

    private fun publishTours() {
        _tours.value = rawTours.map { it.copy(visible = it.id in visibleIds) }
    }

    // ---- Loading --------------------------------------------------------------

    fun importLocalFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _status.value = null
            _syncProgress.value = SyncProgress("Reading files", 0, uris.size)
            val loaded = mutableListOf<LoadedGpx>()
            val errors = mutableListOf<GpxLoadError>()
            try {
                uris.forEachIndexed { index, uri ->
                    try {
                        loaded.add(FileImport.read(getApplication(), uri))
                    } catch (e: Exception) {
                        errors.add(GpxLoadError(uri.lastPathSegment ?: "file", e.message ?: "read failed"))
                    }
                    _syncProgress.value = SyncProgress("Reading files", index + 1, uris.size)
                }
                _syncProgress.value = SyncProgress("Parsing tours…")
                parseAndAdd(loaded, errors)
            } finally {
                _syncProgress.value = null
                _isLoading.value = false
            }
        }
    }

    fun syncDropbox() {
        if (!dropbox.isLinked || _isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _status.value = null
            _syncProgress.value = SyncProgress("Listing Dropbox files…")
            try {
                val files = dropbox.listGpxFiles()
                if (files.isEmpty()) {
                    _status.value = "No .gpx files found in ${dropbox.folderPath.ifEmpty { "/" }}"
                    return@launch
                }
                _syncProgress.value = SyncProgress("Downloading", 0, files.size)
                val (loaded, dlErrors) = dropbox.downloadAll(files) { done, total ->
                    _syncProgress.value = SyncProgress("Downloading", done, total)
                }
                _syncProgress.value = SyncProgress("Parsing tours…")
                parseAndAdd(loaded, dlErrors.toMutableList())
            } catch (e: Exception) {
                _status.value = "Dropbox error: ${e.message}"
            } finally {
                _syncProgress.value = null
                _isLoading.value = false
            }
        }
    }

    private suspend fun parseAndAdd(loaded: List<LoadedGpx>, errors: MutableList<GpxLoadError>) {
        val parsed = withContext(Dispatchers.Default) {
            loaded.mapNotNull { item ->
                try {
                    GpxParser.parse(item.content, item.filename, TourPalette.at(colorSeed++))
                } catch (e: Exception) {
                    errors.add(GpxLoadError(item.filename, e.message ?: "parse failed"))
                    null
                }
            }
        }

        if (parsed.isNotEmpty()) {
            val byId = LinkedHashMap<String, Tour>()
            rawTours.forEach { byId[it.id] = it }
            parsed.forEach { byId[it.id] = it }
            rawTours = byId.values.sortedWith(
                compareByDescending<Tour> { it.startTimeMs ?: Long.MIN_VALUE }
                    .thenBy { it.filename },
            )
            visibleIds = visibleIds + parsed.map { it.id }
            publishTours()
            _fitEvent.value += 1
        }

        _status.value = buildString {
            if (parsed.isNotEmpty()) append("Loaded ${parsed.size} tour(s). ")
            if (errors.isNotEmpty()) append("${errors.size} failed: ${errors.take(3).joinToString { it.filename }}")
        }.trim().ifEmpty { null }
    }

    fun clearStatus() { _status.value = null }

    // ---- Dropbox link ---------------------------------------------------------

    fun connectDropbox(activity: Activity) {
        dropbox.startAuth(activity)
    }

    /** Call from Activity.onResume to finish an in-progress authorization. */
    fun refreshDropboxLink() {
        val newlyLinked = dropbox.completeAuthIfPresent()
        _dropboxLinked.value = dropbox.isLinked
        if (newlyLinked) {
            _status.value = "Dropbox connected. Set a folder (optional), then Sync."
        }
    }

    fun disconnectDropbox() {
        dropbox.unlink()
        _dropboxLinked.value = false
        _status.value = "Dropbox disconnected."
    }

    fun setDropboxFolder(path: String) {
        _dropboxFolder.value = path
        dropbox.folderPath = path
    }

    override fun onCleared() {
        locationTracker.stop()
        super.onCleared()
    }

    init {
        // Keep the HUD tracker in sync with follow + permission state.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(_isFollowing, _hasLocationPermission) { _, _ -> }
                .collect { updateTracker() }
        }
    }
}
