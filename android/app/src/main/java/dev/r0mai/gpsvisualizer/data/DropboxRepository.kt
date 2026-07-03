package dev.r0mai.gpsvisualizer.data

import android.app.Activity
import android.content.Context
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.json.JsonReadException
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import dev.r0mai.gpsvisualizer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Loads GPX files from Dropbox using the official SDK with PKCE + offline
 * (refresh-token) auth, mirroring the web app's flow and reusing its app key.
 * The credential (incl. refresh token) is persisted so the link survives
 * restarts and refreshes automatically.
 */
class DropboxRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("dropbox", Context.MODE_PRIVATE)
    private val requestConfig = DbxRequestConfig.newBuilder("gps-visualizer-android/1.0").build()

    private var client: DbxClientV2? = null

    val isLinked: Boolean
        get() = prefs.getString(KEY_CREDENTIAL, null) != null

    var folderPath: String
        get() = prefs.getString(KEY_FOLDER, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FOLDER, value).apply()
        }

    /** Launch the Dropbox authorization flow (browser / Dropbox app). */
    fun startAuth(activity: Activity) {
        Auth.startOAuth2PKCE(
            activity,
            BuildConfig.DROPBOX_APP_KEY,
            requestConfig,
            listOf("files.metadata.read", "files.content.read"),
        )
    }

    /**
     * Call from Activity.onResume. If we've just returned from authorization,
     * persist the resulting credential. Returns true if a new link was stored.
     */
    fun completeAuthIfPresent(): Boolean {
        if (isLinked) return false
        val credential = Auth.getDbxCredential() ?: return false
        prefs.edit().putString(KEY_CREDENTIAL, DbxCredential.Writer.writeToString(credential)).apply()
        client = null
        return true
    }

    fun unlink() {
        prefs.edit().remove(KEY_CREDENTIAL).apply()
        client = null
    }

    private fun requireClient(): DbxClientV2 {
        client?.let { return it }
        val serialized = prefs.getString(KEY_CREDENTIAL, null)
            ?: throw IllegalStateException("Not linked to Dropbox")
        val credential = try {
            DbxCredential.Reader.readFully(serialized)
        } catch (e: JsonReadException) {
            unlink()
            throw IllegalStateException("Dropbox session invalid, please reconnect.", e)
        }
        return DbxClientV2(requestConfig, credential).also { client = it }
    }

    /** List every .gpx file under [folderPath] (recursively). */
    suspend fun listGpxFiles(): List<FileMetadata> = withContext(Dispatchers.IO) {
        val files = requireClient().files()
        val out = mutableListOf<FileMetadata>()
        var result = files.listFolderBuilder(normalizePath(folderPath))
            .withRecursive(true)
            .start()
        while (true) {
            result.entries.forEach { entry ->
                if (entry is FileMetadata && entry.name.endsWith(".gpx", ignoreCase = true)) {
                    out.add(entry)
                }
            }
            if (!result.hasMore) break
            result = files.listFolderContinue(result.cursor)
        }
        out
    }

    /**
     * Download all [files], up to 6 concurrently, reporting progress. Successes
     * land in the returned pair's first list, failures in the second.
     */
    suspend fun downloadAll(
        files: List<FileMetadata>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Pair<List<LoadedGpx>, List<GpxLoadError>> = coroutineScope {
        val semaphore = Semaphore(6)
        var done = 0
        val total = files.size
        val loaded = mutableListOf<LoadedGpx>()
        val errors = mutableListOf<GpxLoadError>()
        val lock = Any()

        files.map { file ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val text = requireClient().files().download(file.pathLower).use { d ->
                            d.inputStream.readBytes().toString(Charsets.UTF_8)
                        }
                        synchronized(lock) { loaded.add(LoadedGpx(file.name, text)) }
                    } catch (e: Exception) {
                        synchronized(lock) {
                            errors.add(GpxLoadError(file.name, e.message ?: "Download failed"))
                        }
                    } finally {
                        val d = synchronized(lock) { ++done }
                        onProgress(d, total)
                    }
                }
            }
        }.forEach { it.await() }

        loaded to errors
    }

    private fun normalizePath(path: String): String {
        var p = path.trim()
        if (p.isEmpty() || p == "/") return ""
        if (!p.startsWith("/")) p = "/$p"
        if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }

    companion object {
        private const val KEY_CREDENTIAL = "credential"
        private const val KEY_FOLDER = "folder_path"
    }
}
