package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        val title: String,
        val version: String,
        val slug: String?,
        val folders: List<String>,
        val installedAt: Long?,
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

    data class UninstallResult(val removed: List<String>, val remaining: List<String>)

    // A download can contain several top-level add-ons (Details ships its core plus plugins); they share one slug and
    // must be fetched and updated as a unit.

    data class Toc(val title: String?, val version: String?, val interfaceVersion: String?)

    /** Scans the game's AddOns folder and merges in what the manager remembers about each add-on. */
    fun installed(context: Context, addonsDir: File): List<InstalledAddon> =
        groupInstalled(addonsDir, loadRecords(context))

    /**
     * Groups folders by Wago slug. A project like Details installs several top-level add-ons at once; the manager
     * record ties them together so "installed" shows one row and one update. Folders without a record stay manual.
     */
    internal fun groupInstalled(addonsDir: File, records: Map<String, Record>): List<InstalledAddon> {
        if (!addonsDir.isDirectory) return emptyList()
        val tocs = HashMap<String, Toc?>()
        (addonsDir.listFiles() ?: emptyArray())
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .forEach { dir ->
                val toc = findToc(dir) ?: return@forEach
                tocs[dir.name] = readToc(toc)
            }
        if (tocs.isEmpty()) return emptyList()

        val covered = HashSet<String>()
        val grouped = ArrayList<InstalledAddon>()
        records.values.forEach { record ->
            val folders = record.folders.filter { it in tocs }.distinct()
            if (folders.isEmpty()) return@forEach
            covered += folders
            val primary = folders.first()
            val meta = tocs[primary]
            grouped += InstalledAddon(
                title = record.name.ifBlank { meta?.title?.takeIf { it.isNotBlank() } ?: primary },
                version = record.version.ifBlank { meta?.version.orEmpty() },
                slug = record.slug,
                folders = folders,
                installedAt = record.installedAt,
            )
        }
        tocs.forEach { (folder, meta) ->
            if (folder in covered) return@forEach
            grouped += InstalledAddon(
                title = meta?.title?.takeIf { it.isNotBlank() } ?: folder,
                version = meta?.version.orEmpty(),
                slug = null,
                folders = listOf(folder),
                installedAt = null,
            )
        }
        return grouped.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    /** Downloads the latest release for [slug], replaces its folders and remembers the new version. */
    suspend fun install(
        context: Context,
        addonsDir: File,
        slug: String,
        onProgress: (Float) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        // Read before the download so an interrupted update never loses the folders it still has to clean up.
        val previous = loadRecords(context)[slug]?.folders.orEmpty()
        val addon = WagoAddonSource.addon(slug)
        val release = addon.release
            ?: throw IOException("${addon.name.ifBlank { slug }} 还没有 WoW Forever 版本")
        val zip = File(context.cacheDir, "addon-${slug.replace(NON_FILE_CHARS, "_")}.zip")
        try {
            Net.fetchFile(release.downloadUrl, zip) { progress -> onProgress(progress * 0.9f) }
            onProgress(0.92f)
            val folders = AddonImporter.install(zip, addonsDir)
            deleteFolders(addonsDir, previous.filterNot { it in folders })
            remember(
                context,
                Record(
                    slug = slug,
                    name = addon.name.ifBlank { slug },
                    version = release.version,
                    folders = folders,
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
        val records = loadRecords(context)
        val slugs = addons.mapNotNull { it.slug }.distinct()
        val releases = coroutineScope {
            val semaphore = Semaphore(4)
            slugs.map { slug ->
                async {
                    slug to semaphore.withPermit {
                        try {
                            Result.success(WagoAddonSource.addon(slug).release)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "Couldn't check updates for $slug")
                            Result.failure(e)
                        }
                    }
                }
            }.awaitAll().toMap()
        }

        val updates = LinkedHashMap<String, WagoAddonSource.Release>()
        val failures = LinkedHashSet<String>()
        val unsupported = LinkedHashSet<String>()
        addons.forEach { addon ->
            val slug = addon.slug ?: return@forEach
            val result = releases[slug] ?: return@forEach
            if (result.isFailure) {
                failures += addon.title
                return@forEach
            }
            val release = result.getOrNull()
            if (release == null) {
                unsupported += addon.title
            } else {
                val record = records[slug]
                if (record == null || record.version != release.version) updates[slug] = release
            }
        }
        UpdateCheck(updates, failures.toList(), unsupported.toList())
    }

    /** Deletes every folder named in [names] that is a direct child of [base], refusing path traversal. */
    internal fun deleteFolders(base: File, names: Iterable<String>): List<String> {
        val root = base.canonicalFile
        val removed = ArrayList<String>()
        names.distinct().forEach { name ->
            val dir = runCatching { File(root, name).canonicalFile }.getOrNull()
            if (dir == null || dir.parentFile != root || !dir.isDirectory) {
                Timber.w("Skipping add-on folder outside $root: $name")
                return@forEach
            }
            if (dir.deleteRecursively()) {
                removed += name
            } else {
                Timber.w("Couldn't delete add-on folder ${dir.path}")
            }
        }
        return removed
    }

    /** Removes an installed add-on's folders. SavedVariables under WTF stay untouched. */
    suspend fun uninstall(
        context: Context,
        addonsDir: File,
        addon: InstalledAddon,
    ): UninstallResult = withContext(Dispatchers.IO) {
        val removed = deleteFolders(addonsDir, addon.folders)
        val root = addonsDir.canonicalFile
        val remaining = addon.folders.filter { name ->
            val dir = runCatching { File(root, name).canonicalFile }.getOrNull()
            dir != null && dir.parentFile == root && dir.exists()
        }
        if (remaining.isEmpty() && addon.slug != null) forget(context, addon.slug)
        UninstallResult(removed, remaining)
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
        writeRecords(context, records)
    }

    @Synchronized
    private fun forget(context: Context, slug: String) {
        val records = loadRecords(context)
        if (records.remove(slug) != null) writeRecords(context, records)
    }

    private fun writeRecords(context: Context, records: Map<String, Record>) {
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
