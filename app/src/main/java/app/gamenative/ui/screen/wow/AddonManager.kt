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
    const val SOURCE_WAGO = "wago"
    const val SOURCE_CURSEFORGE = "curseforge"

    private const val PREFS = "wow_addons"
    private const val KEY_RECORDS = "records"

    data class InstalledAddon(
        val title: String,
        val version: String,
        val slug: String?,
        val folders: List<String>,
        val installedAt: Long?,
        val enabled: Boolean = true,
        val source: String = SOURCE_WAGO,
    )

    data class Record(
        val slug: String,
        val name: String,
        val version: String,
        val folders: List<String>,
        val installedAt: Long,
        val enabled: Boolean = true,
        val source: String = SOURCE_WAGO,
        val fileId: Long = 0L,
    )

    data class AvailableUpdate(val version: String, val patch: String?)

    data class InstallResult(val name: String, val folders: List<String>)

    data class UpdateCheck(
        val updates: Map<String, AvailableUpdate>,
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
            val toc = folders[present.first()]?.toc
            grouped += InstalledAddon(
                title = clean(record.name).ifBlank { clean(toc?.title).ifBlank { present.first() } },
                version = clean(record.version).ifBlank { clean(toc?.version) },
                slug = record.slug,
                folders = present,
                installedAt = record.installedAt,
                enabled = present.any { folders[it]?.enabled == true },
                source = record.source,
            )
        }
        manualGroups.forEach { (stem, names) ->
            val present = names.sorted()
            val primary = present.first()
            val meta = folders[primary]?.toc
            grouped += InstalledAddon(
                title = clean(meta?.title).ifBlank { stem },
                version = clean(meta?.version),
                slug = null,
                folders = present,
                installedAt = null,
                enabled = present.any { folders[it]?.enabled == true },
            )
        }
        return grouped.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    /** JSON null reaches us as the literal "null"; neither it nor blank text is a usable field value. */
    internal fun clean(value: String?): String =
        value?.trim().takeUnless { it.isNullOrEmpty() || it.equals("null", ignoreCase = true) }.orEmpty()

    private fun manualGroupKey(folder: String): String {
        val lower = folder.lowercase(Locale.ROOT)
        val cut = lower.indexOfFirst { it == '_' || it == '-' || it == ' ' }
        return if (cut >= 3) lower.substring(0, cut) else lower
    }

    /** Downloads the latest release for [slug] from [source], replaces its folders and remembers the new version. */
    suspend fun install(
        context: Context,
        addonsDir: File,
        slug: String,
        gameVersion: String,
        source: String = SOURCE_WAGO,
        onProgress: (Float) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        // Read before the download so an interrupted update never loses the folders it still has to clean up.
        val previousRecord = loadRecords(context)[slug]
        val effectiveSource = previousRecord?.source ?: source
        val previous = previousRecord?.folders.orEmpty()
        val zip = File(context.cacheDir, "addon-${slug.replace(NON_FILE_CHARS, "_")}.zip")
        try {
            val name: String
            val version: String
            val fileId: Long
            val recordSlug: String
            when (effectiveSource) {
                SOURCE_CURSEFORGE -> {
                    val project = CurseForgeSource.fetchZip(context, slug, gameVersion, zip) { onProgress(it) }
                    val file = project.file ?: throw IOException("${project.title} 没有 Forever 版本")
                    name = project.title
                    version = file.display
                    fileId = file.id
                    recordSlug = project.slug
                }
                else -> {
                    val addon = WagoAddonSource.addon(slug)
                    val release = addon.release
                        ?: throw IOException("${addon.name.ifBlank { slug }} 还没有 WoW Forever 版本")
                    Net.fetchFile(release.downloadUrl, zip) { progress -> onProgress(progress * 0.9f) }
                    name = addon.name.ifBlank { slug }
                    version = release.version
                    fileId = 0L
                    recordSlug = slug
                }
            }
            onProgress(0.92f)
            val folders = AddonImporter.install(zip, addonsDir)
            deleteFolders(addonsDir, previous.filterNot { it in folders })
            if (previousRecord?.enabled == false) setEnabled(addonsDir, folders, false)
            remember(
                context,
                Record(
                    slug = recordSlug,
                    name = name,
                    version = version,
                    folders = folders,
                    installedAt = System.currentTimeMillis(),
                    enabled = previousRecord?.enabled ?: true,
                    source = effectiveSource,
                    fileId = fileId,
                ),
            )
            // The project changed its slug: keep one record instead of an orphaned duplicate.
            if (recordSlug != slug && previousRecord != null) forget(context, slug)
            onProgress(1f)
            InstallResult(name, folders)
        } finally {
            zip.delete()
        }
    }

    /**
     * Fetches the latest release for every manager-installed add-on from the source it was installed from. Network
     * failures stay per add-on so one unreachable project doesn't hide the rest; add-ons without a Forever release
     * are reported separately.
     */
    suspend fun checkUpdates(
        context: Context,
        addons: List<InstalledAddon>,
        gameVersion: String,
        force: Boolean = false,
    ): UpdateCheck = withContext(Dispatchers.IO) {
        val records = loadRecords(context)
        val slugs = addons.mapNotNull { it.slug }.distinct()
        val bySlug = addons.filter { it.slug != null }.associateBy { it.slug!! }
        val (wago, curseForge) = slugs.partition { bySlug[it]?.source != SOURCE_CURSEFORGE }
        val failures = LinkedHashSet<String>()
        val unsupported = LinkedHashSet<String>()

        val (wagoResults, forgeResults) = coroutineScope {
            val semaphore = Semaphore(4)
            val wagoTask = async {
                wago.map { slug ->
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
            val forgeTask = async {
                curseForge.map { slug ->
                    async {
                        slug to semaphore.withPermit {
                            try {
                                Result.success(
                                    CurseForgeSource.project(
                                        context,
                                        slug,
                                        gameVersion,
                                        maxAgeMs = if (force) 0 else CurseForgeSource.CACHE_TTL_MS,
                                    ),
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w(e, "Couldn't check CurseForge updates for $slug")
                                Result.failure(e)
                            }
                        }
                    }
                }.awaitAll().toMap()
            }
            wagoTask.await() to forgeTask.await()
        }

        val updates = LinkedHashMap<String, AvailableUpdate>()
        addons.forEach { addon ->
            val slug = addon.slug ?: return@forEach
            if (addon.source == SOURCE_CURSEFORGE) {
                val result = forgeResults[slug] ?: return@forEach
                if (result.isFailure) {
                    failures += addon.title
                    return@forEach
                }
                val file = result.getOrThrow().file
                if (file == null) {
                    unsupported += addon.title
                } else if (isCurseForgeUpdate(records[slug], file.id, file.display)) {
                    updates[slug] = AvailableUpdate(file.display, null)
                }
                return@forEach
            }
            val result = wagoResults[slug] ?: return@forEach
            if (result.isFailure) {
                failures += addon.title
                return@forEach
            }
            val release = result.getOrNull()
            if (release == null) {
                unsupported += addon.title
            } else {
                val record = records[slug]
                if (record == null || record.version != release.version) {
                    updates[slug] = AvailableUpdate(release.version, release.patch)
                }
            }
        }
        UpdateCheck(updates, failures.toList(), unsupported.toList())
    }

    /** A CurseForge record is stale when it points at another file, or at no file from before file ids were stored. */
    internal fun isCurseForgeUpdate(record: Record?, fileId: Long, version: String): Boolean =
        record == null || record.fileId != fileId || (record.fileId == 0L && record.version != version)

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
        takeover(records, record)
        records[record.slug] = record
        writeRecords(context, records)
    }

    /**
     * Hands folders shared with [incoming] over to it. Installing the same add-on from another source leaves two
     * records pointing at one folder set; without this, uninstalling either one deletes folders the other still uses.
     */
    internal fun takeover(records: MutableMap<String, Record>, incoming: Record) {
        records.values
            .filter { it.slug != incoming.slug && it.folders.any { folder -> folder in incoming.folders } }
            .forEach { other ->
                val remaining = other.folders.filterNot { it in incoming.folders }
                if (remaining.isEmpty()) records.remove(other.slug)
                else records[other.slug] = other.copy(folders = remaining)
            }
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
                name = clean(json.optString("name")).ifBlank { slug },
                version = clean(json.optString("version")),
                folders = folders,
                installedAt = json.optLong("installedAt"),
                enabled = json.optBoolean("enabled", true),
                source = json.optString("source").takeIf { it == SOURCE_CURSEFORGE } ?: SOURCE_WAGO,
                fileId = json.optLong("fileId", 0L),
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
                    .put("enabled", record.enabled)
                    .put("source", record.source)
                    .put("fileId", record.fileId),
            )
        }
        return root.toString()
    }

    private val NON_FILE_CHARS = Regex("[^a-zA-Z0-9._-]")
}
