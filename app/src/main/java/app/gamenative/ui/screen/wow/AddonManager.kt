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
        val enabled: Boolean = true,
    )

    data class Record(
        val slug: String,
        val name: String,
        val version: String,
        val folders: List<String>,
        val installedAt: Long,
        val enabled: Boolean = true,
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

    private data class FolderState(val toc: Toc?, val enabled: Boolean)

    /**
     * Groups folders by Wago slug, and groups unrecorded ZIP installs by their shared folder prefix (Polestar,
     * Details, DBM...). Folders without a shared prefix stay single manual rows.
     */
    internal fun groupInstalled(addonsDir: File, records: Map<String, Record>): List<InstalledAddon> {
        if (!addonsDir.isDirectory) return emptyList()
        val folders = LinkedHashMap<String, FolderState>()
        (addonsDir.listFiles() ?: emptyArray())
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .forEach { dir ->
                val toc = findAnyToc(dir) ?: return@forEach
                folders[dir.name] = FolderState(readToc(toc), findToc(dir) != null)
            }
        if (folders.isEmpty()) return emptyList()

        val covered = HashSet<String>()
        val recordFolders = LinkedHashMap<String, MutableList<String>>()
        val recordByStem = HashMap<String, String>()
        records.values.forEach { record ->
            val present = record.folders.filter { it in folders }.distinct()
            recordFolders[record.slug] = present.toMutableList()
            covered += present
            present.firstOrNull()?.let { folder ->
                recordByStem.putIfAbsent(manualGroupKey(folder), record.slug)
            }
        }

        val manualGroups = LinkedHashMap<String, MutableList<String>>()
        folders.keys.filterNot { it in covered }.sorted().forEach { folder ->
            val stem = manualGroupKey(folder)
            val recordSlug = recordByStem[stem]
            if (recordSlug != null) {
                recordFolders.getValue(recordSlug) += folder
            } else {
                manualGroups.getOrPut(stem) { ArrayList() } += folder
            }
        }

        val grouped = ArrayList<InstalledAddon>()
        records.values.forEach { record ->
            val present = recordFolders[record.slug].orEmpty().distinct()
            if (present.isEmpty()) return@forEach
            grouped += InstalledAddon(
                title = record.name.ifBlank { folders[present.first()]?.toc?.title ?: present.first() },
                version = record.version.ifBlank { folders[present.first()]?.toc?.version.orEmpty() },
                slug = record.slug,
                folders = present,
                installedAt = record.installedAt,
                enabled = present.any { folders[it]?.enabled == true },
            )
        }
        manualGroups.forEach { (stem, names) ->
            val present = names.sorted()
            val primary = present.first()
            val meta = folders[primary]?.toc
            grouped += InstalledAddon(
                title = meta?.title?.takeIf { it.isNotBlank() } ?: stem,
                version = meta?.version.orEmpty(),
                slug = null,
                folders = present,
                installedAt = null,
                enabled = present.any { folders[it]?.enabled == true },
            )
        }
        return grouped.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun manualGroupKey(folder: String): String {
        val lower = folder.lowercase(Locale.ROOT)
        val cut = lower.indexOfFirst { it == '_' || it == '-' || it == ' ' }
        return if (cut >= 3) lower.substring(0, cut) else lower
    }

    /** Downloads the latest release for [slug], replaces its folders and remembers the new version. */
    suspend fun install(
        context: Context,
        addonsDir: File,
        slug: String,
        onProgress: (Float) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        // Read before the download so an interrupted update never loses the folders it still has to clean up.
        val previousRecord = loadRecords(context)[slug]
        val previous = previousRecord?.folders.orEmpty()
        val addon = WagoAddonSource.addon(slug)
        val release = addon.release
            ?: throw IOException("${addon.name.ifBlank { slug }} 还没有 WoW Forever 版本")
        val zip = File(context.cacheDir, "addon-${slug.replace(NON_FILE_CHARS, "_")}.zip")
        try {
            Net.fetchFile(release.downloadUrl, zip) { progress -> onProgress(progress * 0.9f) }
            onProgress(0.92f)
            val folders = AddonImporter.install(zip, addonsDir)
            deleteFolders(addonsDir, previous.filterNot { it in folders })
            if (previousRecord?.enabled == false) setEnabled(addonsDir, folders, false)
            remember(
                context,
                Record(
                    slug = slug,
                    name = addon.name.ifBlank { slug },
                    version = release.version,
                    folders = folders,
                    installedAt = System.currentTimeMillis(),
                    enabled = previousRecord?.enabled ?: true,
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
                    val result: Result<WagoAddonSource.Release?> = semaphore.withPermit {
                        try {
                            Result.success(WagoAddonSource.addon(slug).release)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "Couldn't check updates for $slug")
                            Result.failure(e)
                        }
                    }
                    slug to result
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

    /** Enables or disables every top-level .toc in the add-on's folders by renaming it to .toc.disabled. */
    fun setEnabled(addonsDir: File, addon: InstalledAddon, enabled: Boolean): List<String> =
        setEnabled(addonsDir, addon.folders, enabled)

    internal fun setEnabled(addonsDir: File, folders: Iterable<String>, enabled: Boolean): List<String> {
        val root = addonsDir.canonicalFile
        val failed = ArrayList<String>()
        folders.distinct().forEach { name ->
            val dir = runCatching { File(root, name).canonicalFile }.getOrNull()
            if (dir == null || dir.parentFile != root || !dir.isDirectory) {
                failed += name
                return@forEach
            }
            val ok = dir.listFiles()
                ?.filter { it.isFile }
                ?.map { file -> if (enabled) enableToc(file) else disableToc(file) }
                ?.all { it } ?: true
            if (!ok) failed += name
        }
        return failed
    }

    private fun disableToc(file: File): Boolean {
        if (!file.extension.equals("toc", ignoreCase = true)) return true
        val dest = File(file.parentFile, file.name + ".disabled")
        if (dest.exists() && !dest.delete()) {
            Timber.w("Couldn't replace ${dest.path}")
            return false
        }
        return file.renameTo(dest)
    }

    private fun enableToc(file: File): Boolean {
        if (!file.name.endsWith(".toc.disabled", ignoreCase = true)) return true
        val dest = File(file.parentFile, file.name.dropLast(".disabled".length))
        if (dest.exists() && !dest.delete()) {
            Timber.w("Couldn't replace ${dest.path}")
            return false
        }
        return file.renameTo(dest)
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

    /** Finds a .toc that this manager disabled by renaming it to .toc.disabled. */
    internal fun findAnyToc(dir: File): File? {
        findToc(dir)?.let { return it }
        val tocs = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".toc.disabled", ignoreCase = true) }
            .orEmpty()
        return tocs.firstOrNull { tocBaseName(it.name).equals(dir.name, ignoreCase = true) }
            ?: tocs.firstOrNull {
                tocBaseName(it.name).substringBefore('_').equals(dir.name, ignoreCase = true)
            }
            ?: tocs.minByOrNull { it.name.length }
    }

    private fun tocBaseName(name: String): String {
        var base = name
        if (base.endsWith(".disabled", ignoreCase = true)) base = base.dropLast(".disabled".length)
        if (base.endsWith(".toc", ignoreCase = true)) base = base.dropLast(".toc".length)
        return base
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
                enabled = json.optBoolean("enabled", true),
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
                    .put("installedAt", record.installedAt)
                    .put("enabled", record.enabled),
            )
        }
        return root.toString()
    }

    private val NON_FILE_CHARS = Regex("[^a-zA-Z0-9._-]")
}
