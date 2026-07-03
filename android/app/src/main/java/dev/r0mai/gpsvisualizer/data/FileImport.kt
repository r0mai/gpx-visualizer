package dev.r0mai.gpsvisualizer.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads GPX files chosen through the Storage Access Framework (SAF). */
object FileImport {

    suspend fun read(context: Context, uri: Uri): LoadedGpx = withContext(Dispatchers.IO) {
        val name = displayName(context, uri) ?: uri.lastPathSegment ?: "track.gpx"
        val content = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("Could not open file")
        LoadedGpx(name, content)
    }

    private fun displayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }
}
