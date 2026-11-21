package app.gamenative.ui.screen.wow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DriverCatalogTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "contents/adrenotools")
        root.deleteRecursively()
        context.getSharedPreferences("wow_driver", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun parseCatalog_readsValidEntriesAndSkipsUnsafeOnes() {
        val validSha = "a".repeat(64)
        val json = """
            {
              "version": 1,
              "updatedAt": "2026-11-20",
              "drivers": [
                {
                  "name": "Turnip-Test",
                  "family": "a8xx",
                  "tuDebug": "nolrz",
                  "note": "fast",
                  "size": 1234,
                  "sha256": "$validSha",
                  "urls": ["https://example.com/driver.zip"]
                },
                {"name": "../evil", "family": "a8xx", "urls": ["https://example.com/x.zip"]},
                {"name": "a/b", "family": "a8xx", "urls": ["https://example.com/x.zip"]},
                {"name": "HttpOnly", "family": "a8xx", "urls": ["http://example.com/x.zip"]},
                {"name": "BadSha", "family": "a8xx", "sha256": "abc", "urls": ["https://example.com/x.zip"]}
              ]
            }
        """.trimIndent()

        val catalog = DriverCatalog.parseCatalog(json)

        assertNotNull(catalog)
        assertEquals(1, catalog!!.version)
        assertEquals("2026-11-20", catalog.updatedAt)
        assertEquals(1, catalog.drivers.size)
        val driver = catalog.drivers.single()
        assertEquals("Turnip-Test", driver.name)
        assertEquals("a8xx", driver.family)
        assertEquals("fast", driver.note)
        assertEquals(1234L, driver.size)
        assertEquals(validSha, driver.sha256)
        assertEquals(listOf("https://example.com/driver.zip"), driver.urls)
    }

    @Test
    fun driverDir_rejectsTraversal() {
        val base = tempFolder.newFolder("adrenotools")

        assertEquals(File(base.canonicalFile, "safe"), DriverCatalog.driverDir(base, "safe"))
        assertThrows(IllegalArgumentException::class.java) {
            DriverCatalog.driverDir(base, "../x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DriverCatalog.driverDir(base, "a/b")
        }
    }

    @Test
    fun installArchive_replacesOldDriver() {
        val target = File(root, "Custom").apply { mkdirs() }
        File(target, "old.txt").writeText("old")
        val zip = tempFolder.newFile("driver.zip")
        writeZip(
            zip,
            mapOf(
                "meta.json" to """{"name":"Custom","driverVersion":"1.0","libraryName":"libvulkan.so"}""",
                "libs/new.txt" to "new",
            ),
        )

        val installed = DriverCatalog.installArchive(zip, root, "Custom")

        assertFalse(File(target, "old.txt").exists())
        assertTrue(File(target, "libs/new.txt").isFile)
        assertEquals("Custom", installed.displayName)
        assertEquals("1.0", installed.version)
        assertEquals("libvulkan.so", installed.libraryName)
        assertTrue(installed.sizeBytes > 0L)
    }

    @Test
    fun installArchive_rejectsZipSlipAndKeepsOldDriver() {
        val target = File(root, "Custom").apply { mkdirs() }
        File(target, "old.txt").writeText("old")
        val zip = tempFolder.newFile("driver.zip")
        writeZip(zip, mapOf("../evil.txt" to "evil"))

        assertThrows(IOException::class.java) {
            DriverCatalog.installArchive(zip, root, "Custom")
        }

        assertTrue(File(target, "old.txt").isFile)
        assertFalse(File(root, "evil.txt").exists())
    }

    @Test
    fun mergeOptions_dedupesBundledInstalledAndRemote() {
        val family = "a8xx"
        val bundled = DriverCatalog.candidates(family)
        val name = bundled.first { it.recommended }.name
        val installed = InstalledDriver(name, "Installed", "1.0", "libvulkan.so", 10L)
        val remote = RemoteDriver(
            name = name,
            family = family,
            tuDebug = "nolrz",
            note = "online",
            size = 20L,
            sha256 = "b".repeat(64),
            urls = listOf("https://example.com/driver.zip"),
        )
        val downloaded = mapOf(name to DownloadedDriver("nolrz", "online", remote.sha256, 1L))
        val active = DriverEntry(name, family, "nolrz", "online", true, true)

        val options = DriverCatalog.mergeOptions(
            family = family,
            bundled = bundled,
            installed = listOf(installed),
            remote = listOf(remote),
            downloaded = downloaded,
            active = active,
        )

        val matches = options.filter { it.name == name }
        assertEquals(1, matches.size)
        val option = matches.single()
        assertTrue(option.bundled)
        assertEquals(installed, option.installed)
        assertEquals(remote, option.remote)
        assertEquals(downloaded[name], option.downloaded)
        assertTrue(option.active)
        assertEquals(0, options.indexOfFirst { it.recommended })

        val old = InstalledDriver("Old", "Old", "0.9", "libvulkan.so", 5L)
        val offline = DriverCatalog.mergeOptions(
            family = family,
            bundled = bundled,
            installed = listOf(old),
            remote = emptyList(),
            downloaded = emptyMap(),
            active = active,
        )
        assertTrue(offline.any { it.name == "Old" && it.installed == old })
    }

    @Test
    fun resolveForLaunch_fallsBackWhenSelectedDriverIsMissing() {
        DriverCatalog.save(context, DriverEntry("OnlineOnly", "a8xx", "nolrz", "note", false, false))

        val effective = DriverCatalog.resolveForLaunch(context, "a8xx")

        val recommended = DriverCatalog.candidates("a8xx").first { it.recommended }
        assertEquals(recommended.name, effective.name)
        assertEquals(recommended.name, DriverCatalog.current(context, "a8xx").name)
    }

    @Test
    fun resolveForLaunch_keepsInstalledOnlineDriver() {
        val dir = File(root, "OnlineOnly").apply { mkdirs() }
        File(dir, "meta.json").writeText(
            """{"name":"OnlineOnly","driverVersion":"2.0","libraryName":"libvulkan.so"}""",
        )
        DriverCatalog.save(context, DriverEntry("OnlineOnly", "a8xx", "nolrz", "note", false, false))

        val effective = DriverCatalog.resolveForLaunch(context, "a8xx")

        assertEquals("OnlineOnly", effective.name)
    }
}
