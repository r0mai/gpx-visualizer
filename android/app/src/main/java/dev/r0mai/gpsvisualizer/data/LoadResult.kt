package dev.r0mai.gpsvisualizer.data

/** Raw GPX text fetched from a local file or Dropbox, ready to parse. */
data class LoadedGpx(val filename: String, val content: String)

/** A file that could not be fetched or parsed. */
data class GpxLoadError(val filename: String, val message: String)
