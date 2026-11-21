package app.gamenative.ui.screen.wow

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfigProfileStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var gameRoot: File
    private lateinit var addonsDir: File

    private val live: File get() = ConfigProfileStore.liveWtf(gameRoot)
    private val root: File get() = ConfigProfileStore.root(gameRoot)
    private val journal: File get() = File(root, ".switch.json")

    @Before
    fun setUp() {
        gameRoot = tempFolder.newFolder("game")
        addonsDir = File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/Interface/AddOns")
        toc("WeakAuras")
        toc("Details")
        File(live, "Account/acct/SavedVariables").mkdirs()
        File(live, "Account/acct/SavedVariables/WeakAuras.lua").writeText("console: original")
        File(live, "Config.wtf").writeText("SET gxApi \"D3D11\"\nSET synchronizeConfig 1\n")
    }

    private fun toc(name: String) {
        val dir = File(addonsDir, name).apply { mkdirs() }
        File(dir, "$name.toc").writeText("## Title: $name\n## Version: 1.0\n")
    }

    private fun liveLua(): String =
        File(live, "Account/acct/SavedVariables/WeakAuras.lua").readText()

    private fun setLiveLua(text: String) {
        File(live, "Account/acct/SavedVariables/WeakAuras.lua").writeText(text)
    }

    private fun snapshotLua(id: String): String =
        File(root, "$id/WTF/Account/acct/SavedVariables/WeakAuras.lua").readText()

    private fun configLines(): List<String> = File(live, "Config.wtf").readLines().filter { it.isNotBlank() }

    private fun writeProfile(id: String, name: String, enabled: List<String>) {
        val dir = File(root, id).apply { mkdirs() }
        val addons = JSONArray()
        enabled.forEach(addons::put)
        File(dir, "profile.json").writeText(
            JSONObject()
                .put("version", 1)
                .put("name", name)
                .put("createdAt", 1L)
                .put("updatedAt", 1L)
                .put("enabledAddons", addons)
                .toString(),
        )
    }

    private fun writeMeta(activeId: String?) {
        root.mkdirs()
        File(root, "profiles.json").writeText(
            JSONObject()
                .put("version", 1)
                .put("activeId", activeId.orEmpty())
                .put("takeOverBlizzardSettings", true)
                .toString(),
        )
    }

    private fun writeJournal(from: String?, to: String) {
        root.mkdirs()
        journal.writeText(
            JSONObject()
                .put("version", 1)
                .put("from", from ?: JSONObject.NULL)
                .put("to", to)
                .toString(),
        )
    }

    @Test
    fun createFirstProfileKeepsLiveWtfAndCapturesEnabledAddons() {
        val state = ConfigProfileStore.create(gameRoot, addonsDir, "手柄")

        val profile = state.profiles.single()
        assertEquals(profile.id, state.activeId)
        assertEquals("手柄", profile.name)
        assertFalse(profile.hasSnapshot)
        assertEquals(listOf("Details", "WeakAuras"), profile.enabledAddons)
        assertTrue(live.isDirectory)
        assertEquals("console: original", liveLua())
        assertFalse(File(root, "${profile.id}/WTF").exists())
    }

    @Test
    fun createSecondProfileSnapshotsActiveAndMarksNewActive() {
        val first = ConfigProfileStore.create(gameRoot, addonsDir, "手柄")
        val firstId = first.activeId!!

        val second = ConfigProfileStore.create(gameRoot, addonsDir, "键盘")

        assertEquals(2, second.profiles.size)
        val target = second.profiles.first { it.id == firstId }
        assertTrue(target.hasSnapshot)
        assertEquals("console: original", snapshotLua(firstId))
        assertEquals(listOf("Details", "WeakAuras"), target.enabledAddons)
        val created = second.profiles.first { it.id != firstId }
        assertFalse(created.hasSnapshot)
        assertEquals(created.id, second.activeId)
        assertTrue(live.isDirectory)
        assertEquals("console: original", liveLua())
    }

    @Test
    fun activateSwapsWtfAndAppliesAddonFlags() {
        val handheld = ConfigProfileStore.create(gameRoot, addonsDir, "手柄").activeId!!
        val keyboard = ConfigProfileStore.create(gameRoot, addonsDir, "键盘").activeId!!

        setLiveLua("console: keyboard")
        assertTrue(AddonManager.setEnabled(addonsDir, listOf("Details"), false).isEmpty())

        // Switching back restores the handheld snapshot, including Details being available again.
        val switched = ConfigProfileStore.activate(gameRoot, addonsDir, handheld)
        assertEquals(handheld, switched.activeId)
        assertEquals("console: original", liveLua())
        assertTrue(File(addonsDir, "Details/Details.toc").isFile)
        assertFalse(File(addonsDir, "Details/Details.toc.disabled").exists())
        val keyboardProfile = switched.profiles.first { it.id == keyboard }
        assertTrue(keyboardProfile.hasSnapshot)
        assertEquals(listOf("WeakAuras"), keyboardProfile.enabledAddons)
        assertEquals("console: keyboard", snapshotLua(keyboard))

        // And going forward again hands the modified live state to the previous profile.
        setLiveLua("console: handheld 2")
        val back = ConfigProfileStore.activate(gameRoot, addonsDir, keyboard)
        assertEquals(keyboard, back.activeId)
        assertEquals("console: keyboard", liveLua())
        assertTrue(File(addonsDir, "Details/Details.toc.disabled").isFile)
        assertFalse(File(addonsDir, "Details/Details.toc").exists())
        assertEquals("console: handheld 2", snapshotLua(handheld))
        assertFalse(journal.exists())
    }

    @Test
    fun recoverFinishesSwitchWhenLiveMissing() {
        writeProfile("aaaaaaaa", "from", listOf("WeakAuras"))
        writeProfile("bbbbbbbb", "to", listOf("Details"))
        File(root, "aaaaaaaa/WTF/Account/acct/SavedVariables").mkdirs()
        File(root, "aaaaaaaa/WTF/Account/acct/SavedVariables/WeakAuras.lua").writeText("console: from")
        File(root, "bbbbbbbb/WTF/Account/acct/SavedVariables").mkdirs()
        File(root, "bbbbbbbb/WTF/Account/acct/SavedVariables/WeakAuras.lua").writeText("console: to")
        writeMeta("aaaaaaaa")
        writeJournal("aaaaaaaa", "bbbbbbbb")
        live.deleteRecursively()

        val state = ConfigProfileStore.load(gameRoot, addonsDir)

        assertEquals("bbbbbbbb", state.activeId)
        assertEquals("console: to", liveLua())
        assertFalse(journal.exists())
        assertTrue(File(addonsDir, "WeakAuras/WeakAuras.toc.disabled").isFile)
        assertTrue(File(addonsDir, "Details/Details.toc").isFile)
    }

    @Test
    fun recoverRedoesSwitchWhenTargetSnapshotStillPresent() {
        writeProfile("aaaaaaaa", "from", listOf("WeakAuras"))
        writeProfile("bbbbbbbb", "to", listOf("WeakAuras"))
        File(root, "bbbbbbbb/WTF/Account/acct/SavedVariables").mkdirs()
        File(root, "bbbbbbbb/WTF/Account/acct/SavedVariables/WeakAuras.lua").writeText("console: to")
        writeMeta("aaaaaaaa")
        writeJournal("aaaaaaaa", "bbbbbbbb")

        val state = ConfigProfileStore.load(gameRoot, addonsDir)

        assertEquals("bbbbbbbb", state.activeId)
        assertEquals("console: to", liveLua())
        assertEquals("console: original", snapshotLua("aaaaaaaa"))
        assertFalse(journal.exists())
    }

    @Test
    fun deleteRejectsActiveAndRemovesInactive() {
        val handheld = ConfigProfileStore.create(gameRoot, addonsDir, "手柄").activeId!!
        val keyboard = ConfigProfileStore.create(gameRoot, addonsDir, "键盘").activeId!!

        try {
            ConfigProfileStore.delete(gameRoot, addonsDir, keyboard)
            fail("The active profile must not be deletable")
        } catch (e: IllegalArgumentException) {
            assertEquals("当前使用的方案不能删除，请先切换到其他方案", e.message)
        }

        val after = ConfigProfileStore.delete(gameRoot, addonsDir, handheld)
        assertEquals(1, after.profiles.size)
        assertEquals(keyboard, after.activeId)
        assertFalse(File(root, handheld).exists())
    }

    @Test
    fun duplicateNameRejected() {
        ConfigProfileStore.create(gameRoot, addonsDir, "Handheld")

        try {
            ConfigProfileStore.create(gameRoot, addonsDir, " handheld ")
            fail("Names must be unique regardless of case")
        } catch (e: IllegalArgumentException) {
            assertEquals("方案名称已存在", e.message)
        }
        try {
            ConfigProfileStore.create(gameRoot, addonsDir, "   ")
            fail("Blank names are rejected")
        } catch (e: IllegalArgumentException) {
            assertEquals("方案名称不能为空", e.message)
        }
    }

    @Test
    fun journalRejectsPathTraversal() {
        writeProfile("bbbbbbbb", "to", listOf("WeakAuras"))
        File(root, "bbbbbbbb/WTF").mkdirs()
        val outside = File(gameRoot, "evil/WTF").apply { mkdirs() }
        File(outside, "sentinel.txt").writeText("keep")
        writeJournal("../evil", "bbbbbbbb")

        val state = ConfigProfileStore.load(gameRoot, addonsDir)

        assertEquals("keep", File(outside, "sentinel.txt").readText())
        assertEquals("console: original", liveLua())
        assertFalse(journal.exists())
        assertNull(state.activeId)
    }

    @Test
    fun applyLaunchPolicyWritesAndClearsSyncCvars() {
        ConfigProfileStore.create(gameRoot, addonsDir, "手柄")

        ConfigProfileStore.applyLaunchPolicy(gameRoot)
        assertEquals(
            listOf(
                "SET gxApi \"D3D11\"",
                "SET synchronizeConfig 0",
                "SET synchronizeBindings 0",
                "SET synchronizeMacros 0",
                "SET synchronizeChatFrames 0",
            ),
            configLines(),
        )

        val state = ConfigProfileStore.setTakeOverBlizzardSettings(gameRoot, false)

        assertFalse(state.takeOverBlizzardSettings)
        assertEquals(listOf("SET gxApi \"D3D11\""), configLines())
    }
}
