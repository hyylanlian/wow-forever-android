package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.utils.Net
import com.winlator.core.FileUtils
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

internal data class DriverEntry(
    val name: String,
    val family: String,
    val tuDebug: String,
    val note: String,
    val recommended: Boolean,
    val bundled: Boolean,
)

internal data class RemoteDriver(
    val name: String,
    val family: String,
    val tuDebug: String,
    val note: String,
    val size: Long,
    val sha256: String?,
    val urls: List<String>,
)

internal data class RemoteCatalog(val version: Int, val updatedAt: String, val drivers: List<RemoteDriver>)

internal data class InstalledDriver(
    val name: String,
    val displayName: String,
    val version: String,
    val libraryName: String,
    val sizeBytes: Long,
)

internal data class DownloadedDriver(
    val tuDebug: String,
    val note: String,
    val sha256: String?,
    val at: Long,
)

internal data class DriverMeta(val name: String, val version: String, val libraryName: String)

internal data class DriverOption(
    val name: String,
    val family: String,
    val tuDebug: String,
    val note: String,
    val recommended: Boolean,
    val bundled: Boolean,
    val installed: InstalledDriver?,
    val remote: RemoteDriver?,
    val downloaded: DownloadedDriver?,
    val active: Boolean,
)

/**
 * Turnip driver catalogue, local installation and launch-time fallback. The old launcher kept this state inline;
 * the manager needs the same disk layout but with install scanning, deletion and an online catalogue cache.
 */
internal object DriverCatalog {
    internal val BUNDLED_ASSETS = linkedMapOf(
        "Turnip-WoW-scheduler-test" to "turnip-wow-scheduler-test.zip",
        "Turnip-V32-RP6sched" to "turnip-V32-RP6sched.zip",
    )

    private val CATALOG = mapOf(
        "a8xx" to listOf(
            DriverEntry(
                "Turnip-V32-RP6sched",
                "a8xx",
                "nolrz",
                "Tuned for Adreno 8xx (RP6 scheduler build)",
                recommended = true,
                bundled = true,
            ),
            DriverEntry(
                "Turnip-WoW-scheduler-test",
                "a8xx",
                "noconform",
                "Adreno 7xx default; try it if the recommended one misrenders",
                recommended = false,
                bundled = true,
            ),
        ),
        "a7xx" to listOf(
            DriverEntry(
                "Turnip-WoW-scheduler-test",
                "a7xx",
                "noconform",
                "Stable default for Adreno 740",
                recommended = true,
                bundled = true,
            ),
            DriverEntry(
                "Turnip-V32-RP6sched",
                "a7xx",
                "nolrz",
                "Gen8 build; try it if the default one misrenders",
                recommended = false,
                bundled = true,
            ),
        ),
    )

    private const val PREFS = "wow_driver"
    private const val KEY_NAME = "driver"
    private const val KEY_TU = "driver_tudebug"
    private const val KEY_NOTE = "driver_note"
    private const val KEY_DOWNLOADS = "downloads"

    private const val CATALOG_RAW =
        "https://raw.githubusercontent.com/hyylanlian/wow-forever-android/main/drivers.json"
    private val CATALOG_URLS = listOf(
        "https://gh-proxy.com/$CATALOG_RAW",
        "https://ghfast.top/$CATALOG_RAW",
        CATALOG_RAW,
    )

    internal const val CATALOG_TTL_MS = 12 * 60 * 60 * 1000L
    private const val STAGING_SUFFIX = ".staging"
    private const val BACKUP_SUFFIX = ".backup"
    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val FAMILIES = setOf("a6xx", "a7xx", "a8xx")
    private const val HEX = "0123456789abcdef"

    fun candidates(family: String): List<DriverEntry> = CATALOG[family] ?: CATALOG.getValue("a7xx")

    fun isBundled(name: String): Boolean = name in BUNDLED_ASSETS

    fun current(context: Context, family: String): DriverEntry {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_NAME, null)
        val savedTu = prefs.getString(KEY_TU, null)
        val savedNote = prefs.getString(KEY_NOTE, null)
        if (saved != null) {
            val candidate = candidates(family).firstOrNull { it.name == saved }
            if (candidate != null) {
                // The same name can also come from the online catalogue with a different TU_DEBUG.
                return candidate.copy(
                    tuDebug = savedTu ?: candidate.tuDebug,
                    note = savedNote ?: candidate.note,
                )
            }
            return DriverEntry(
                name = saved,
                family = family,
                tuDebug = savedTu ?: "nolrz",
                note = savedNote ?: "",
                recommended = false,
                bundled = isBundled(saved),
            )
        }
        return candidates(family).first()
    }

    fun save(context: Context, entry: DriverEntry) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, entry.name)
            .putString(KEY_TU, entry.tuDebug)
            .putString(KEY_NOTE, entry.note)
            .apply()
    }

    /**
     * The driver launch will actually use. Bundled drivers are installed by prepareLaunch; an online driver whose
     * folder disappeared resets the saved choice instead of silently starting on the system driver.
     */
    fun resolveForLaunch(context: Context, family: String): DriverEntry {
        val chosen = current(context, family)
        if (isBundled(chosen.name)) return chosen
        val meta = runCatching { readMeta(driverDir(root(context), chosen.name)) }.getOrNull()
        if (meta != null && meta.libraryName.isNotBlank()) return chosen
        val recommended = candidates(family).first { it.recommended }
        save(context, recommended)
        Timber.w("Selected driver %s is missing; falling back to %s", chosen.name, recommended.name)
        return recommended
    }

    fun installed(context: Context): List<InstalledDriver> {
        val root = root(context)
        if (!root.isDirectory) return emptyList()
        recoverInterrupted(root)
        return (root.listFiles() ?: emptyArray())
            .asSequence()
            .filter {
                it.isDirectory &&
                    !it.name.endsWith(STAGING_SUFFIX) &&
                    !it.name.endsWith(BACKUP_SUFFIX)
            }
            .mapNotNull { dir ->
                val safe = runCatching { driverDir(root, dir.name) }.getOrNull() ?: return@mapNotNull null
                val meta = readMeta(safe) ?: return@mapNotNull null
                InstalledDriver(
                    name = safe.name,
                    displayName = meta.name.takeIf { it.isNotBlank() && it != "null" } ?: safe.name,
                    version = meta.version,
                    libraryName = meta.libraryName,
                    sizeBytes = safe.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                )
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .toList()
    }

    fun downloaded(context: Context): Map<String, DownloadedDriver> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DOWNLOADS, null)
        if (raw.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, DownloadedDriver>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val json = root.optJSONObject(name) ?: continue
            result[name] = DownloadedDriver(
                tuDebug = json.optString("tuDebug", "nolrz").ifBlank { "nolrz" },
                note = json.optString("note", ""),
                sha256 = json.optString("sha256", "").takeIf { it.isNotBlank() && it != "null" },
                at = json.optLong("at", 0L),
            )
        }
        return result
    }

    @Synchronized
    internal fun rememberDownload(context: Context, driver: RemoteDriver) {
        val all = downloaded(context).toMutableMap()
        all[driver.name] = DownloadedDriver(
            tuDebug = driver.tuDebug,
            note = driver.note,
            sha256 = driver.sha256,
            at = System.currentTimeMillis(),
        )
        writeDownloads(context, all)
    }

    @Synchronized
    private fun forgetDownload(context: Context, name: String) {
        val all = downloaded(context).toMutableMap()
        if (all.remove(name) != null) writeDownloads(context, all)
    }

    private fun writeDownloads(context: Context, all: Map<String, DownloadedDriver>) {
        val json = JSONObject()
        all.forEach { (name, driver) ->
            json.put(
                name,
                JSONObject()
                    .put("tuDebug", driver.tuDebug)
                    .put("note", driver.note)
                    .put("sha256", driver.sha256)
                    .put("at", driver.at),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DOWNLOADS, json.toString())
            .apply()
    }

    fun cachedRemote(context: Context): RemoteCatalog? {
        val file = cacheFile(context)
        if (!file.isFile) return null
        return runCatching { parseCatalog(file.readText()) }.getOrNull()
    }

    fun catalogAgeMs(context: Context): Long {
        val file = cacheFile(context)
        if (!file.isFile) return Long.MAX_VALUE
        return System.currentTimeMillis() - file.lastModified()
    }

    suspend fun fetchRemote(context: Context): Result<RemoteCatalog> = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "drivers.json")
        var last: Exception? = null
        for (url in CATALOG_URLS) {
            try {
                Net.fetchFile(url, tmp) {}
                val catalog = parseCatalog(tmp.readText()) ?: throw IOException("驱动目录格式无效")
                runCatching {
                    tmp.copyTo(cacheFile(context), overwrite = true)
                }.onFailure { Timber.w(it, "Couldn't cache the driver catalog") }
                return@withContext Result.success(catalog)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        Timber.w(last, "Couldn't fetch the driver catalog")
        Result.failure(last ?: IOException("无可用驱动目录地址"))
    }

    suspend fun download(
        context: Context,
        driver: RemoteDriver,
        onProgress: (Float) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        if (!driver.name.matches(SAFE_NAME) || driver.name == "." || driver.name == "..") {
            throw IOException("非法驱动名")
        }
        val root = root(context)
        val zip = File(context.cacheDir, "driver-${driver.name}.zip")
        var last: Exception? = null
        try {
            for (url in driver.urls.filter { it.startsWith("https://") }) {
                try {
                    Net.fetchFile(url, zip) { progress -> onProgress(progress * 0.9f) }
                    if (driver.sha256 != null && !sha256(zip).equals(driver.sha256, true)) {
                        throw IOException("驱动校验失败")
                    }
                    installArchive(zip, root, driver.name)
                    onProgress(1f)
                    runCatching { rememberDownload(context, driver) }
                        .onFailure { Timber.w(it, "Couldn't record the downloaded driver") }
                    return@withContext
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                    zip.delete()
                }
            }
            throw IOException("驱动下载失败：${last?.message ?: "无可用地址"}", last)
        } finally {
            zip.delete()
        }
    }

    fun delete(context: Context, family: String, name: String): Boolean {
        if (isBundled(name)) return false
        val root = root(context)
        val target = runCatching { driverDir(root, name) }.getOrNull() ?: return false
        if (current(context, family).name == name) {
            save(context, candidates(family).first { it.recommended })
        }
        target.deleteRecursively()
        if (target.exists()) return false
        forgetDownload(context, name)
        return true
    }

    internal fun parseCatalog(json: String): RemoteCatalog? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val array = root.optJSONArray("drivers") ?: return null
        val drivers = ArrayList<RemoteDriver>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            val family = o.optString("family", "")
            if (!name.matches(SAFE_NAME) || name == "." || name == "..") continue
            if (family !in FAMILIES) continue
            val urls = o.optJSONArray("urls")?.let { entries ->
                (0 until entries.length()).mapNotNull { index ->
                    entries.optString(index, "").takeIf { it.startsWith("https://") }
                }
            }.orEmpty()
            if (urls.isEmpty()) continue
            val sha = o.optString("sha256", "").takeIf { it.isNotBlank() && it != "null" }
            if (sha != null && !SHA256.matches(sha)) continue
            drivers += RemoteDriver(
                name = name,
                family = family,
                tuDebug = o.optString("tuDebug", "nolrz").ifBlank { "nolrz" },
                note = o.optString("note", ""),
                size = o.optLong("size", -1L),
                sha256 = sha,
                urls = urls,
            )
        }
        return RemoteCatalog(
            version = root.optInt("version", 0),
            updatedAt = root.optString("updatedAt", ""),
            drivers = drivers,
        )
    }

    internal fun driverDir(base: File, name: String): File {
        require(name.matches(SAFE_NAME) && name != "." && name != "..") { "Invalid driver name: $name" }
        val root = base.canonicalFile
        val dir = File(root, name).canonicalFile
        require(dir.parentFile == root) { "Invalid driver path: $name" }
        return dir
    }

    internal fun readMeta(dir: File): DriverMeta? {
        val file = File(dir, "meta.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val library = json.optString("libraryName", "")
            if (library.isBlank() || library == "null") return@runCatching null
            DriverMeta(
                name = json.optString("name", ""),
                version = json.optString("driverVersion", ""),
                libraryName = library,
            )
        }.getOrNull()
    }

    /**
     * Repairs a download interrupted between the two renames. A leftover backup only replaces its target when the
     * target is gone; otherwise it is stale and removed.
     */
    internal fun recoverInterrupted(root: File) {
        val entries = root.listFiles() ?: return
        entries.filter { it.isDirectory && it.name.endsWith(STAGING_SUFFIX) }
            .forEach { it.deleteRecursively() }
        entries.filter { it.isDirectory && it.name.endsWith(BACKUP_SUFFIX) }
            .forEach { backup ->
                val name = backup.name.removeSuffix(BACKUP_SUFFIX)
                val target = runCatching { driverDir(root, name) }.getOrNull()
                if (target == null) {
                    backup.deleteRecursively()
                    return@forEach
                }
                if (!target.exists()) {
                    if (!backup.renameTo(target)) Timber.w("Couldn't restore driver ${target.path}")
                } else {
                    backup.deleteRecursively()
                }
            }
    }

    internal fun installArchive(zip: File, root: File, name: String): InstalledDriver {
        val target = driverDir(root, name)
        val staging = File(root, "$name$STAGING_SUFFIX")
        val backup = File(root, "$name$BACKUP_SUFFIX")
        val rootCanonical = root.canonicalFile
        require(staging.canonicalFile.parentFile == rootCanonical) { "Invalid staging path: $name" }
        require(backup.canonicalFile.parentFile == rootCanonical) { "Invalid backup path: $name" }
        root.mkdirs()
        recoverInterrupted(root)
        staging.deleteRecursively()
        backup.deleteRecursively()
        try {
            validateZip(zip, staging)
            if (!FileUtils.extractZipFromFile(zip, staging)) throw IOException("驱动包无法解压")
            val meta = readMeta(staging) ?: throw IOException("驱动包缺少 meta.json 或 libraryName")
            if (meta.libraryName.isBlank()) throw IOException("驱动包缺少 meta.json 或 libraryName")

            if (target.exists() && !target.renameTo(backup)) {
                throw IOException("无法替换旧驱动")
            }
            if (!staging.renameTo(target)) {
                if (backup.exists() && !backup.renameTo(target)) {
                    Timber.w("Couldn't restore old driver ${target.path}")
                }
                throw IOException("无法写入驱动目录")
            }
            backup.deleteRecursively()
            val installed = readMeta(target) ?: throw IOException("驱动包缺少 meta.json 或 libraryName")
            return InstalledDriver(
                name = target.name,
                displayName = installed.name.takeIf { it.isNotBlank() && it != "null" } ?: target.name,
                version = installed.version,
                libraryName = installed.libraryName,
                sizeBytes = target.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun validateZip(zip: File, staging: File) {
        val base = staging.canonicalFile
        val prefix = base.path + File.separator
        ZipFile(zip).use { archive ->
            archive.entries().asSequence().forEach { entry ->
                val dest = File(base, entry.name).canonicalFile
                if (dest.path != base.path && !dest.path.startsWith(prefix)) {
                    throw IOException("Invalid path in driver zip: ${entry.name}")
                }
            }
        }
    }

    internal fun mergeOptions(
        family: String,
        bundled: List<DriverEntry>,
        installed: List<InstalledDriver>,
        remote: List<RemoteDriver>,
        downloaded: Map<String, DownloadedDriver>,
        active: DriverEntry,
    ): List<DriverOption> {
        val bundledByName = bundled.associateBy { it.name }
        val installedByName = installed.associateBy { it.name }
        val remoteByName = remote.filter { it.family == family }.associateBy { it.name }
        val names = LinkedHashSet<String>()
        bundled.forEach { names += it.name }
        installed.forEach { names += it.name }
        remote.filter { it.family == family }.forEach { names += it.name }
        if (active.family == family) names += active.name

        return names.map { name ->
            val bundledEntry = bundledByName[name]
            val installedEntry = installedByName[name]
            val remoteEntry = remoteByName[name]
            val download = downloaded[name]
            val isActive = name == active.name && active.family == family
            val optionFamily = bundledEntry?.family ?: if (isActive) active.family else remoteEntry?.family ?: family
            DriverOption(
                name = name,
                family = optionFamily,
                tuDebug = when {
                    isActive -> active.tuDebug
                    download != null -> download.tuDebug
                    bundledEntry != null -> bundledEntry.tuDebug
                    remoteEntry != null -> remoteEntry.tuDebug
                    else -> "nolrz"
                },
                note = when {
                    isActive -> active.note
                    download != null -> download.note
                    bundledEntry != null -> bundledEntry.note
                    remoteEntry != null -> remoteEntry.note
                    else -> ""
                },
                recommended = bundledEntry?.recommended ?: false,
                bundled = bundledEntry != null,
                installed = installedEntry,
                remote = remoteEntry,
                downloaded = download,
                active = isActive,
            )
        }.sortedWith(
            compareBy<DriverOption> {
                when {
                    it.recommended -> 0
                    it.bundled -> 1
                    it.installed != null -> 2
                    else -> 3
                }
            }.thenBy { it.name.lowercase(Locale.ROOT) },
        )
    }

    private fun root(context: Context): File = File(context.filesDir, "contents/adrenotools")

    private fun cacheFile(context: Context): File = File(context.filesDir, "wow_drivers.json")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        val result = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            result.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return result.toString()
    }
}
