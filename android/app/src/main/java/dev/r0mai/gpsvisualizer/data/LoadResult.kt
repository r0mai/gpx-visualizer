package dev.r0mai.gpsvisualizer.data

/** Raw GPX text fetched from a local file or Dropbox, ready to parse. */
data class LoadedGpx(val filename: String, val content: String)

/** A file that could not be fetched or parsed. */
data class GpxLoadError(val filename: String, val message: String)

/**
 * Progress of a load operation (Dropbox sync or local import), surfaced to the
 * UI. When [total] is 0 the phase is indeterminate (e.g. still listing files).
 */
data class SyncProgress(
    val phase: String,
    val done: Int = 0,
    val total: Int = 0,
) {
    /** 0f..1f for a determinate bar, or null when the total is not yet known. */
    val fraction: Float?
        get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    val label: String
        get() = if (total > 0) "$phase $done/$total" else phase
}
