package app.gamenative.ui.screen.wow

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Whole-configuration profiles for the game install. A profile owns a WTF snapshot (SavedVariables, layout,
 * bindings-cache, macros-cache, config-cache, Config.wtf) plus the set of add-on folders allowed to load.
 * Switching only moves directories and renames .toc files, before the game starts; the install stays the
 * single source of truth and nothing inside the add-ons themselves is patched.
 */
internal object ConfigProfileStore {
    private const val DIR_NAME = "LauncherProfiles"
    private const val PROFILES_JSON = "profiles.json"
    private const val PROFILE_JSON = "profile.json"
    private const val JOURNAL_JSON = ".switch.json"
    private const val WTF = "WTF"
    private const val CONFIG_WTF = "Config.wtf"
    private const val STAGING = "WTF.copying"
    private const val VERSION = 1

    /** Also used to guard paths: ids come from our own files, but a hand-edited journal must not escape root. */
    private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

    /**
     * Blizzard's account-wide sync would otherwise overwrite the local bindings, macros, layout and per-account
     * cvar cache at login, which is exactly what a WTF snapshot is supposed to switch.
     */
    private val SYNC_KEYS = listOf(
        "synchronizeConfig",
        "synchronizeBindings",
        "synchronizeMacros",
        "synchronizeChatFrames",
    )

    data class Profile(
        val id: String,
        val name: String,
        val createdAt: Long,
        val updatedAt: Long,
        val enabledAddons: List<String>,
        val hasSnapshot: Boolean,
    )

    data class State(
        val activeId: String?,
        val profiles: List<Profile>,
        val takeOverBlizzardSettings: Boolean,
    )

    private data class Meta(val activeId: String?, val takeOver: Boolean)

    private data class Journal(val from: String?, val to: String)

    fun root(gameRoot: File): File = File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/$DIR_NAME")

    fun liveWtf(gameRoot: File): File = File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/$WTF")

    private fun addonsDir(gameRoot: File): File =
        File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/Interface/AddOns")

    /** Reads the profiles, finishing an interrupted switch and restoring a snapshot the game folder lost. */
    fun load(gameRoot: File, addonsDir: File): State {
        if (File(root(gameRoot), JOURNAL_JSON).isFile) recover(gameRoot, addonsDir)

        val root = root(gameRoot)
        val meta = readMeta(root)
        val activeDir = meta.activeId?.let { safeChild(root, it) }
        // Without a journal the live WTF should be in place; a snapshot alone means it was moved away by hand.
        if (activeDir != null && File(activeDir, WTF).isDirectory && !liveWtf(gameRoot).exists()) {
            runCatching { moveDirectory(File(activeDir, WTF), liveWtf(gameRoot)) }
                .onFailure { Timber.w(it, "Couldn't restore ${liveWtf(gameRoot).path}") }
        }
        (root.listFiles() ?: emptyArray()).forEach { dir ->
            if (dir.isDirectory) File(dir, STAGING).deleteRecursively()
        }

        val profiles = (root.listFiles() ?: emptyArray())
            .asSequence()
            .filter { it.isDirectory && ID_PATTERN.matches(it.name) }
            .mapNotNull { readProfile(it) }
            .sortedWith(compareBy<Profile>({ it.createdAt }, { it.id }))
            .toList()
        val activeId = meta.activeId?.takeIf { id -> profiles.any { it.id == id } }
        return State(activeId, profiles, meta.takeOver)
    }

    /**
     * Creates a profile. With an active profile first, the current WTF is copied into its snapshot and the new
     * profile starts from the same state; without one, the new profile simply claims the live folder on switch.
     */
    fun create(
        gameRoot: File,
        addonsDir: File,
        name: String,
        onProgress: (Float) -> Unit = {},
    ): State {
        val trimmed = validateName(name)
        val state = load(gameRoot, addonsDir)
        requireUniqueName(state, trimmed, exceptId = null)

        state.activeId?.let { activeId ->
            val activeDir = safeChild(root(gameRoot), activeId)
                ?: throw IllegalStateException("当前方案目录缺失")
            if (!activeDir.isDirectory) throw IllegalStateException("当前方案目录缺失")
            val live = liveWtf(gameRoot)
            if (live.isDirectory) {
                val staging = File(activeDir, STAGING)
                staging.deleteRecursively()
                try {
                    copyDirectory(live, staging, onProgress)
                } catch (e: Exception) {
                    staging.deleteRecursively()
                    throw e
                }
                val snapshot = File(activeDir, WTF)
                snapshot.deleteRecursively()
                moveDirectory(staging, snapshot)
                onProgress(1f)
            }
            // Adding a profile is also the moment the previous one learns what its own add-on set was.
            updateManifest(activeDir, AddonManager.enabledFolders(addonsDir))
        }

        val id = UUID.randomUUID().toString()
        val dir = File(root(gameRoot), id)
        val now = System.currentTimeMillis()
        writeProfile(
            dir,
            Profile(id, trimmed, now, now, AddonManager.enabledFolders(addonsDir), hasSnapshot = false),
        )
        writeActive(gameRoot, id)
        return load(gameRoot, addonsDir)
    }

    /**
     * Swaps the live WTF with the target's snapshot. The journal written first makes an interrupted swap
     * recoverable: [load] finishes or rolls it back without ever leaving two half-swapped folders.
     */
    fun activate(
        gameRoot: File,
        addonsDir: File,
        id: String,
        onProgress: (Float) -> Unit = {},
    ): State {
        recover(gameRoot, addonsDir)
        val state = load(gameRoot, addonsDir)
        if (state.activeId == id) return state

        val root = root(gameRoot)
        val targetDir = safeChild(root, id)
        if (targetDir == null || !targetDir.isDirectory || !File(targetDir, WTF).isDirectory) {
            throw IllegalArgumentException("该方案没有配置快照，请删除后重建")
        }

        var from = state.activeId
        if (from == null && liveWtf(gameRoot).isDirectory) {
            // Nothing owned the live WTF yet: keep it as a profile instead of overwriting the only copy.
            from = claimLive(gameRoot, addonsDir, state)
        }
        writeJournal(gameRoot, from, id)
        try {
            if (from != null) {
                val fromDir = safeChild(root, from) ?: throw IllegalStateException("当前方案目录缺失")
                snapshotLive(gameRoot, fromDir, addonsDir, onProgress)
            }
            val live = liveWtf(gameRoot)
            if (live.exists() && !live.deleteRecursively()) throw IOException("无法替换 ${live.path}")
            moveDirectory(File(targetDir, WTF), live)
            applyAddons(addonsDir, targetDir)
            writeActive(gameRoot, id)
            File(root, JOURNAL_JSON).delete()
            onProgress(1f)
            return load(gameRoot, addonsDir)
        } catch (e: Exception) {
            runCatching { recover(gameRoot, addonsDir) }
            // The swap may have completed before the failing step reported it; recovery is the source of truth.
            val recovered = runCatching { load(gameRoot, addonsDir) }.getOrNull()
            if (recovered?.activeId == id) return recovered
            throw e
        }
    }

    fun rename(gameRoot: File, addonsDir: File, id: String, name: String): State {
        val trimmed = validateName(name)
        val state = load(gameRoot, addonsDir)
        val dir = safeChild(root(gameRoot), id)?.takeIf { it.isDirectory }
            ?: throw IllegalArgumentException("方案不存在")
        val current = readProfile(dir) ?: throw IllegalArgumentException("方案不存在")
        requireUniqueName(state, trimmed, exceptId = id)
        writeProfile(dir, current.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
        return load(gameRoot, addonsDir)
    }

    fun delete(gameRoot: File, addonsDir: File, id: String): State {
        val state = load(gameRoot, addonsDir)
        if (state.activeId == id) {
            throw IllegalArgumentException("当前使用的方案不能删除，请先切换到其他方案")
        }
        val dir = safeChild(root(gameRoot), id)?.takeIf { it.isDirectory }
            ?: throw IllegalArgumentException("方案不存在")
        if (!dir.deleteRecursively()) throw IOException("无法删除方案目录：${dir.path}")
        return load(gameRoot, addonsDir)
    }

    /** Records the sync takeover choice; turning it off also drops the zeroed cvars so the client syncs again. */
    fun setTakeOverBlizzardSettings(gameRoot: File, enabled: Boolean): State {
        val root = root(gameRoot)
        writeMeta(gameRoot, readMeta(root).copy(takeOver = enabled))
        if (!enabled) clearSyncCvars(gameRoot)
        return load(gameRoot, addonsDir(gameRoot))
    }

    /** Writes the sync cvars right before launch, after the profile's own Config.wtf has been moved in. */
    fun applyLaunchPolicy(gameRoot: File) {
        val meta = readMeta(root(gameRoot))
        if (meta.activeId == null || !meta.takeOver) return
        val configWtf = File(liveWtf(gameRoot), CONFIG_WTF)
        if (!configWtf.isFile) return
        val forced = SYNC_KEYS.map { "SET $it 0" }
        val original = runCatching { configWtf.readLines() }.getOrNull() ?: return
        if (original.containsAll(forced)) return
        val kept = original.filterNot { line -> SYNC_KEYS.any { line.startsWith("SET $it ", ignoreCase = true) } }
        configWtf.writeText((kept + forced).joinToString("\n") + "\n")
    }

    /**
     * Finishes or rolls back an interrupted [activate]. The journal records the old and the new profile; the
     * folders that still exist decide whether the swap needs redoing, finishing, rolling back, or nothing.
     */
    fun recover(gameRoot: File, addonsDir: File) {
        val root = root(gameRoot)
        val journalFile = File(root, JOURNAL_JSON)
        if (!journalFile.isFile) return
        val journal = readJournal(journalFile)
        if (journal == null) {
            journalFile.delete()
            return
        }
        val toDir = safeChild(root, journal.to)
        if (toDir == null || !toDir.isDirectory) {
            journalFile.delete()
            return
        }
        val fromDir = journal.from?.let { safeChild(root, it) }
        val live = liveWtf(gameRoot)
        val toWtf = File(toDir, WTF)
        val fromWtf = fromDir?.let { File(it, WTF) }

        fun finishSwitch() {
            applyAddons(addonsDir, toDir)
            writeActive(gameRoot, journal.to)
            journalFile.delete()
        }

        fun moveSnapshotIn() {
            moveDirectory(toWtf, live)
            finishSwitch()
        }

        fun redoSnapshot() {
            val dir = fromDir ?: return
            val oldSnapshot = fromWtf ?: return
            updateManifest(dir, AddonManager.enabledFolders(addonsDir))
            oldSnapshot.deleteRecursively()
            moveDirectory(live, oldSnapshot)
            moveSnapshotIn()
        }

        if (journal.from == null) {
            when {
                // A journal without a source must never overwrite the only WTF on disk.
                live.isDirectory -> journalFile.delete()
                toWtf.isDirectory -> moveSnapshotIn()
                else -> journalFile.delete()
            }
            return
        }

        val hasLive = live.isDirectory
        val hasTo = toWtf.isDirectory
        val hasFrom = fromWtf?.isDirectory == true
        when {
            hasLive && hasTo -> redoSnapshot()
            hasLive -> finishSwitch()
            hasTo -> moveSnapshotIn()
            hasFrom -> {
                fromWtf?.let { moveDirectory(it, live) }
                writeActive(gameRoot, journal.from)
                journalFile.delete()
            }
            else -> journalFile.delete()
        }
    }

    private fun claimLive(gameRoot: File, addonsDir: File, state: State): String {
        val taken = state.profiles.map { it.name.lowercase(Locale.ROOT) }.toSet()
        var name = "当前配置"
        var suffix = 2
        while (name.lowercase(Locale.ROOT) in taken) {
            name = "当前配置 $suffix"
            suffix++
        }
        val id = UUID.randomUUID().toString()
        val dir = File(root(gameRoot), id)
        val now = System.currentTimeMillis()
        writeProfile(
            dir,
            Profile(id, name, now, now, AddonManager.enabledFolders(addonsDir), hasSnapshot = false),
        )
        return id
    }

    /** Copies the live WTF into [dir]/WTF, after refreshing the add-on list that belongs to that snapshot. */
    private fun snapshotLive(
        gameRoot: File,
        dir: File,
        addonsDir: File,
        onProgress: (Float) -> Unit,
    ) {
        updateManifest(dir, AddonManager.enabledFolders(addonsDir))
        val snapshot = File(dir, WTF)
        snapshot.deleteRecursively()
        val live = liveWtf(gameRoot)
        if (live.isDirectory) moveDirectory(live, snapshot)
        onProgress(1f)
    }

    private fun applyAddons(addonsDir: File, dir: File) {
        val profile = readProfile(dir)
        if (profile == null) {
            // Never treat unreadable metadata as "no add-ons enabled": that would disable every plugin.
            Timber.w("Skipping add-on flags for ${dir.path}: unreadable $PROFILE_JSON")
            return
        }
        AddonManager.applyEnabledFolders(addonsDir, profile.enabledAddons.toSet()).forEach { name ->
            Timber.w("Couldn't apply the add-on state for $name")
        }
    }

    private fun validateName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "方案名称不能为空" }
        require(trimmed.length <= 32) { "方案名称过长（最多 32 个字符）" }
        return trimmed
    }

    private fun requireUniqueName(state: State, name: String, exceptId: String?) {
        val clash = state.profiles.any {
            it.id != exceptId && it.name.equals(name, ignoreCase = true)
        }
        require(!clash) { "方案名称已存在" }
    }

    private fun readMeta(root: File): Meta {
        val json = runCatching { File(root, PROFILES_JSON).takeIf { it.isFile }?.readText() }
            .getOrNull()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Meta(null, true)
        val active = json.optString("activeId").takeIf { it.isNotBlank() && ID_PATTERN.matches(it) }
        return Meta(active, json.optBoolean("takeOverBlizzardSettings", true))
    }

    private fun writeMeta(gameRoot: File, meta: Meta) {
        val root = root(gameRoot)
        root.mkdirs()
        writeJson(
            File(root, PROFILES_JSON),
            JSONObject()
                .put("version", VERSION)
                .put("activeId", meta.activeId.orEmpty())
                .put("takeOverBlizzardSettings", meta.takeOver),
        )
    }

    private fun writeActive(gameRoot: File, id: String?) {
        writeMeta(gameRoot, readMeta(root(gameRoot)).copy(activeId = id))
    }

    private fun readProfile(dir: File): Profile? {
        val json = runCatching { File(dir, PROFILE_JSON).takeIf { it.isFile }?.readText() }
            .getOrNull()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val addons = json.optJSONArray("enabledAddons")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        return Profile(
            id = dir.name,
            name = json.optString("name").trim().ifBlank { dir.name },
            createdAt = json.optLong("createdAt"),
            updatedAt = json.optLong("updatedAt"),
            enabledAddons = addons.sorted(),
            hasSnapshot = File(dir, WTF).isDirectory,
        )
    }

    private fun writeProfile(dir: File, profile: Profile) {
        dir.mkdirs()
        val addons = JSONArray()
        profile.enabledAddons.sorted().forEach { addons.put(it) }
        writeJson(
            File(dir, PROFILE_JSON),
            JSONObject()
                .put("version", VERSION)
                .put("name", profile.name)
                .put("createdAt", profile.createdAt)
                .put("updatedAt", profile.updatedAt)
                .put("enabledAddons", addons),
        )
    }

    /** Read-modify-write of one profile's add-on list; [rename] stays the only way to change a name. */
    private fun updateManifest(dir: File, enabled: List<String>) {
        val current = readProfile(dir)
        if (current == null) {
            Timber.w("Skipping the add-on list for ${dir.path}: unreadable $PROFILE_JSON")
            return
        }
        writeProfile(dir, current.copy(enabledAddons = enabled, updatedAt = System.currentTimeMillis()))
    }

    private fun writeJournal(gameRoot: File, from: String?, to: String) {
        val root = root(gameRoot)
        root.mkdirs()
        writeJson(
            File(root, JOURNAL_JSON),
            JSONObject()
                .put("version", VERSION)
                .put("from", from ?: JSONObject.NULL)
                .put("to", to),
        )
    }

    private fun readJournal(file: File): Journal? {
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val to = json.optString("to").takeIf { ID_PATTERN.matches(it) } ?: return null
        val from = if (json.isNull("from")) null else json.optString("from").takeIf { ID_PATTERN.matches(it) }
        return Journal(from, to)
    }

    private fun clearSyncCvars(gameRoot: File) {
        val configWtf = File(liveWtf(gameRoot), CONFIG_WTF)
        if (!configWtf.isFile) return
        val original = runCatching { configWtf.readLines() }.getOrNull() ?: return
        val kept = original.filterNot { line -> syncValue(line) == "0" }
        if (kept.size != original.size) configWtf.writeText(kept.joinToString("\n") + "\n")
    }

    /** The value of a synchronize* line, with quotes removed; null for every other line. */
    private fun syncValue(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("SET ", ignoreCase = true)) return null
        val rest = trimmed.substring(4).trim()
        val key = rest.substringBefore(' ')
        if (SYNC_KEYS.none { it.equals(key, ignoreCase = true) }) return null
        return rest.substringAfter(' ', "").trim().trim('"')
    }

    private fun safeChild(root: File, name: String): File? {
        if (!ID_PATTERN.matches(name)) return null
        val dir = runCatching { File(root, name).canonicalFile }.getOrNull() ?: return null
        return dir.takeIf { it.parentFile == root.canonicalFile }
    }

    private fun writeJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.toString())
        if (temp.renameTo(file)) return
        file.delete()
        if (!temp.renameTo(file)) throw IOException("Couldn't write ${file.path}")
    }

    /** Copies [from] to [to] keeping empty folders; progress is reported at whole-percent steps. */
    private fun copyDirectory(from: File, to: File, onProgress: (Float) -> Unit) {
        val total = from.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        var copied = 0L
        var lastPercent = -1
        to.mkdirs()
        from.walkTopDown().forEach { src ->
            val dest = File(to, src.relativeTo(from).path)
            if (src.isDirectory) {
                dest.mkdirs()
                return@forEach
            }
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            copied += src.length()
            val percent = if (total > 0) (copied * 100 / total).toInt() else 100
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent / 100f)
            }
        }
        if (lastPercent < 100) onProgress(1f)
    }

    /** rename is O(1) inside the install; FUSE or SD-card installs fall back to copy-then-delete. */
    private fun moveDirectory(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        // The copy fallback must not merge the two folders: callers always move a whole WTF over an old one.
        if (to.exists() && !to.deleteRecursively()) throw IOException("无法替换 ${to.path}")
        copyDirectory(from, to) {}
        if (!from.deleteRecursively()) Timber.w("Couldn't delete ${from.path} after copying it to ${to.path}")
    }
}
