package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Tracks add-ons installed through the Wago catalogue, so their folders can be refreshed in place. The game folder
 * itself stays the source of truth: folders without a record are shown as manual installs.
 */
internal object AddonManager {
    private const val PREFS = "wow_addons"
    private const val KEY_RECORDS = "records"

    data class InstalledAddon(
        val folder: String,
        val title: String,
        val version: String,
        val slug: String?,
    )

    data class Record(
        val slug: String,
        val name: String,
        val version: String,
        val folders: List<String>,
        val installedAt: Long,
    )

    data class InstallResult(
        val addon: WagoAddonSource.Addon,
        val release: WagoAddonSource.Release,
        val folders: List<String>,
    )

    data class UpdateCheck(
        val updates: Map<String, WagoAddonSource.Release>,
        val failures: List<String>,
        val unsupported: List<String>,
    )

    // A download can contain several top-level add-ons (Details ships its core plus plugins); they share one slug and
    // must be fetched and updated as a unit.

    data class Toc(val title: String?, val version: String?, val interfaceVersion: String?)

    /** Scans the game's AddOns folder and merges in what the manager remembers about each add-on. */
    fun installed(context: Context, addonsDir: File): List<InstalledAddon> {
        if (!addonsDir.isDirectory) return emptyList()
        val byFolder = HashMap<String, Record>()
        loadRecords(context).values.forEach { record -> record.folders.forEach { byFolder[it] = record } }
        return (addonsDir.listFiles() ?: emptyArray())
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir ->
                val toc = findToc(dir) ?: return@mapNotNull null
                val meta = readToc(toc)
                val record = byFolder[dir.name]
                InstalledAddon(
                    folder = dir.name,
                    title = meta?.title?.takeIf { it.isNotBlank() } ?: dir.name,
                    version = record?.version ?: meta?.version.orEmpty(),
                    slug = record?.slug,
                )
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
            .toList()
    }

    /** Downloads the latest release for [slug], replaces its folders and remembers the new version. */
    suspend fun install(
        context: Context,
        addonsDir: File,
        slug: String,
        onProgress: (Float) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        val addon = WagoAddonSource.addon(slug)
        val release = addon.release
            ?: throw IOException("${addon.name.ifBlank { slug }} 还没有 WoW Forever 版本")
        val zip = File(context.cacheDir, "addon-${slug.replace(NON_FILE_CHARS, "_")}.zip")
        try {
            Net.fetchFile(release.downloadUrl, zip) { progress -> onProgress(progress * 0.9f) }
            onProgress(0.92f)
            val folders = AddonImporter.install(zip, addonsDir)
            val previous = loadRecords(context)[slug]?.folders.orEmpty()
            remember(
                context,
                Record(
                    slug = slug,
                    name = addon.name.ifBlank { slug },
                    version = release.version,
                    folders = (previous + folders).distinct(),
                    installedAt = System.currentTimeMillis(),
                ),
            )
            onProgress(1f)
            InstallResult(addon, release, folders)
        } finally {
            zip.delete()
        }
    }

    /**
     * Fetches the latest release for every manager-installed add-on. Network failures stay per add-on so one
     * unreachable project doesn't hide the rest; add-ons without a Forever release are reported separately.
     */
    suspend fun checkUpdates(context: Context, addons: List<InstalledAddon>): UpdateCheck = withContext(Dispatchers.IO) {
        val byFolder = HashMap<String, Record>()
        loadRecords(context).values.forEach { record -> record.folders.forEach { byFolder[it] = record } }
        val updates = LinkedHashMap<String, WagoAddonSource.Release>()
        val failures = LinkedHashSet<String>()
        val unsupported = LinkedHashSet<String>()
        // One Wago project can own several installed folders; fetch it once and apply the answer to all of them.
        val releases = HashMap<String, WagoAddonSource.Release?>()
        val failed = HashSet<String>()
        addons.forEach { addon ->
            val slug = addon.slug ?: return@forEach
            if (slug !in releases && slug !in failed) {
                try {
                    releases[slug] = WagoAddonSource.addon(slug).release
                } catch (e: Exception) {
                    Timber.w(e, "Couldn't check updates for $slug")
                    failed += slug
                }
            }
            if (slug in failed) {
                failures += addon.title
                return@forEach
            }
            val release = releases[slug]
            if (release == null) {
                unsupported += addon.title
            } else {
                val record = byFolder[addon.folder]
                if (record == null || record.version != release.version) updates[slug] = release
            }
        }
        UpdateCheck(updates, failures.toList(), unsupported.toList())
    }

    /** The newest .toc at the top level: add-ons keep bundled libraries in subfolders. */
    internal fun findToc(dir: File): File? {
        val tocs = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("toc", ignoreCase = true) }
            .orEmpty()
        return tocs.firstOrNull { it.nameWithoutExtension.equals(dir.name, ignoreCase = true) }
            ?: tocs.firstOrNull { it.nameWithoutExtension.substringBefore('_').equals(dir.name, ignoreCase = true) }
            ?: tocs.minByOrNull { it.name.length }
    }

    internal fun readToc(file: File): Toc? {
        val fields = HashMap<String, String>()
        runCatching {
            file.useLines { lines ->
                lines.forEachIndexed { index, raw ->
                    val line = if (index == 0) raw.removePrefix("\uFEFF") else raw
                    if (!line.startsWith("##")) return@forEachIndexed
                    val colon = line.indexOf(':', 2)
                    if (colon < 0) return@forEachIndexed
                    val key = line.substring(2, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    if (key.isNotEmpty()) fields.putIfAbsent(key, value)
                }
            }
        }.onFailure { Timber.w(it, "Couldn't read ${file.path}") }
        if (fields.isEmpty()) return null
        return Toc(
            title = fields["Title"]?.takeIf { it.isNotBlank() },
            version = fields["Version"]?.removePrefix("#")?.trim()?.takeIf { it.isNotBlank() },
            interfaceVersion = fields["Interface"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun loadRecords(context: Context): MutableMap<String, Record> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, null)
        return decodeRecords(raw).toMutableMap()
    }

    @Synchronized
    private fun remember(context: Context, record: Record) {
        val records = loadRecords(context)
        records[record.slug] = record
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECORDS, encodeRecords(records))
            .apply()
    }

    internal fun decodeRecords(raw: String?): Map<String, Record> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val records = LinkedHashMap<String, Record>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val slug = keys.next()
            val json = root.optJSONObject(slug) ?: continue
            val folders = json.optJSONArray("folders")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
            }.orEmpty()
            records[slug] = Record(
                slug = slug,
                name = json.optString("name").ifBlank { slug },
                version = json.optString("version"),
                folders = folders,
                installedAt = json.optLong("installedAt"),
            )
        }
        return records
    }

    internal fun encodeRecords(records: Map<String, Record>): String {
        val root = JSONObject()
        records.values.forEach { record ->
            val folders = JSONArray()
            record.folders.forEach(folders::put)
            root.put(
                record.slug,
                JSONObject()
                    .put("name", record.name)
                    .put("version", record.version)
                    .put("folders", folders)
                    .put("installedAt", record.installedAt),
            )
        }
        return root.toString()
    }

    private val NON_FILE_CHARS = Regex("[^a-zA-Z0-9._-]")
}
