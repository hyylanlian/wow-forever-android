package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WowClientDownloaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun readBuildInfo_parsesValidPipeDelimitedFile() {
        val root = tempFolder.newFolder("wow")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n" +
            "us|1|9d5b7a136208535a8dfbc971489e8ae8|b1f5f5904d9c7cb3241bc6eeea50cce8|f68b323351f044ef3c9902641775796b|10332|tpr/wow|level3.blizzard.com|?|1|1.15.5.58238|wow_classic_era\n"
        )

        val result = WowClientDownloader.readBuildInfo(buildInfo)
        assertNotNull(result)
        assertEquals("wow_classic_era", result?.get("Product"))
        assertEquals("1.15.5.58238", result?.get("Version"))
    }

    @Test
    fun checkVersion_rejectsWrongProduct() {
        val root = tempFolder.newFolder("classic_era")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n" +
            "us|1|9d5b7a136208535a8dfbc971489e8ae8|b1f5f5904d9c7cb3241bc6eeea50cce8|f68b323351f044ef3c9902641775796b|10332|tpr/wow|level3.blizzard.com|?|1|1.15.5.58238|wow_classic_era\n"
        )

        val result = WowClientDownloader.checkVersion(root, "us")
        assertNull(result)
    }

    @Test
    fun readBuildInfo_selectsForeverFromMultiGameInstall() {
        val root = tempFolder.newFolder("multi_wow")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n" +
            "us|1|11111111111111111111111111111111|22222222222222222222222222222222|33333333333333333333333333333333|10000|tpr/wow|level3.blizzard.com|?|0|11.0.7.58238|wow\n" +
            "us|1|44444444444444444444444444444444|55555555555555555555555555555555|66666666666666666666666666666666|10000|tpr/wow|level3.blizzard.com|?|0|1.15.5.58238|wow_classic_era\n" +
            "us|1|77777777777777777777777777777777|88888888888888888888888888888888|99999999999999999999999999999999|10000|tpr/wow|level3.blizzard.com|?|1|1.60.1.70205|wow_classic_beta\n" +
            "us|1|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb||10000|tpr/wow|level3.blizzard.com|?|1|1.60.1.70658|wowf\n"
        )

        val result = WowClientDownloader.readBuildInfo(buildInfo)
        assertNotNull(result)
        assertEquals("wowf", result?.get("Product"))
        assertEquals("1.60.1.70658", result?.get("Version"))
    }

    @Test
    fun installedProducts_listsBetaWhenForeverMissing() {
        val buildInfo = File(tempFolder.newFolder("beta_only"), ".build.info")
        buildInfo.writeText(BETA_BUILD_INFO)

        assertEquals(setOf("wow_classic_era", "wow_classic_beta"), WowClientDownloader.installedProducts(buildInfo))
        assertEquals(emptySet<String>(), WowClientDownloader.installedProducts(File(tempFolder.root, "missing")))
    }

    @Test
    fun upsertBuildInfo_addsForeverRowAndKeepsBetaRegion() {
        val root = tempFolder.newFolder("beta_upgrade")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(BETA_BUILD_INFO)

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET)

        val lines = buildInfo.readLines()
        assertEquals(4, lines.size)
        assertEquals(BETA_BUILD_INFO.lines().take(3), lines.take(3))
        val row = WowClientDownloader.readBuildInfo(buildInfo)!!
        assertEquals("wowf", row["Product"])
        assertEquals("eu", row["Branch"])
        assertEquals("1", row["Active"])
        assertEquals("1.60.1.70658", row["Version"])
        assertEquals("165410aca770316ea0f88100bb4830c2", row["Build Key"])
        assertEquals("f0fcf03baf658e38f2f6c96a77798a2c", row["CDN Key"])
        assertEquals("level3.blizzard.com us.cdn.blizzard.com", row["CDN Hosts"])
        assertEquals("http://level3.blizzard.com/?maxhosts=8 https://us.cdn.blizzard.com/?maxhosts=4&fallback=1", row["CDN Servers"])
        assertEquals("Windows arm64 EU? enUS speech?:Windows arm64 EU? enUS text?", row["Tags"])
    }

    @Test
    fun regionRow_picksTheRequestedRegionsBuild() {
        val rows = listOf("us" to "70888", "eu" to "70888", "kr" to "70862", "tw" to "70862").map { (region, build) ->
            mapOf("Region" to region, "BuildId" to build)
        }

        assertEquals("70862", WowClientDownloader.regionRow(rows, "Region", "kr")?.get("BuildId"))
        assertEquals("eu", WowClientDownloader.regionRow(rows, "Region", "EU")?.get("Region"))
        assertEquals("us", WowClientDownloader.regionRow(rows, "Region", "cn")?.get("Region"))
        assertNull(WowClientDownloader.regionRow(emptyList(), "Region", "us"))
    }

    @Test
    fun patchUrl_usesTheChinaPatchServerForCn() {
        assertEquals("https://cn.version.battlenet.com.cn/v2/products/wowf", WowClientDownloader.patchUrl("CN"))
        assertEquals("https://us.version.battle.net/v2/products/wowf", WowClientDownloader.patchUrl("us"))
    }

    @Test
    fun upsertBuildInfo_usesChineseTagsForChina() {
        val buildInfo = File(tempFolder.newFolder("fresh_cn"), ".build.info")

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET.copy(region = "cn"))

        val row = WowClientDownloader.readBuildInfo(buildInfo)!!
        assertEquals("cn", row["Branch"])
        assertEquals("Windows arm64 CN? zhCN speech?:Windows arm64 CN? zhCN text?", row["Tags"])
    }

    @Test
    fun upsertBuildInfo_switchesAnExistingRowToChineseTags() {
        val buildInfo = File(tempFolder.newFolder("switch_cn"), ".build.info")
        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET)

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET.copy(region = "cn"))

        val row = WowClientDownloader.readBuildInfo(buildInfo)!!
        assertEquals("cn", row["Branch"])
        assertEquals("Windows arm64 CN? zhCN speech?:Windows arm64 CN? zhCN text?", row["Tags"])
    }

    @Test
    fun upsertBuildInfo_usesTheTargetsRegionForANewRow() {
        val buildInfo = File(tempFolder.newFolder("fresh_kr"), ".build.info")
        buildInfo.writeText(BETA_BUILD_INFO)

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET.copy(region = "kr"))

        val row = WowClientDownloader.readBuildInfo(buildInfo)!!
        assertEquals("kr", row["Branch"])
        assertEquals("Windows arm64 KR? enUS speech?:Windows arm64 KR? enUS text?", row["Tags"])
    }

    @Test
    fun upsertBuildInfo_movesAnExistingRowToTheTargetsRegion() {
        val buildInfo = File(tempFolder.newFolder("switch_region"), ".build.info")
        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET.copy(region = "us"))

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET.copy(region = "kr"))

        val row = WowClientDownloader.readBuildInfo(buildInfo)!!
        assertEquals(2, buildInfo.readLines().size)
        assertEquals("kr", row["Branch"])
        assertEquals("Windows arm64 KR? enUS speech?:Windows arm64 KR? enUS text?", row["Tags"])
    }

    @Test
    fun httpsServers_keepsTheHttpsServersWithoutTheirOptions() {
        val servers = listOf(
            "http://level3.blizzard.com/?maxhosts=8",
            "https://level3.ssl.blizzard.com/?maxhosts=4&fallback=1",
            "https://us.cdn.blizzard.com/?maxhosts=4&fallback=1",
        )

        assertEquals(
            listOf("https://level3.ssl.blizzard.com", "https://us.cdn.blizzard.com"),
            WowClientDownloader.httpsServers(servers, listOf("level3.blizzard.com")),
        )
    }

    @Test
    fun httpsServers_fallsBackToTheHostsOverHttps() {
        assertEquals(
            listOf("https://level3.ssl.blizzard.com", "https://kr.cdn.blizzard.com"),
            WowClientDownloader.httpsServers(listOf("http://level3.blizzard.com/"), listOf("level3.blizzard.com", "kr.cdn.blizzard.com")),
        )
        assertEquals(
            listOf("https://level3.ssl.blizzard.com", "https://us.cdn.blizzard.com"),
            WowClientDownloader.httpsServers(emptyList(), emptyList()),
        )
    }

    @Test
    fun verifyConfigDigest_checksTheContentAgainstItsKey() {
        val data = "build-config-content".toByteArray()
        WowClientDownloader.verifyConfigDigest("CE78972E1A942C496938C2700A19735D", data)
        assertThrows(java.io.IOException::class.java) {
            WowClientDownloader.verifyConfigDigest("0".repeat(32), data)
        }
    }

    @Test
    fun upsertBuildInfo_createsFileForFreshInstall() {
        val buildInfo = File(tempFolder.newFolder("fresh"), ".build.info")

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET)

        val lines = buildInfo.readLines()
        assertEquals(2, lines.size)
        assertEquals(15, lines[0].split("|").size)
        assertEquals(15, lines[1].split("|").size)
        val row = WowClientDownloader.readBuildInfo(buildInfo)!!
        assertEquals("wowf", row["Product"])
        assertEquals("tpr/wow", row["CDN Path"])
        assertEquals("1.60.1.70658", row["Version"])
    }

    @Test
    fun upsertBuildInfo_updatesExistingForeverRowInPlace() {
        val buildInfo = File(tempFolder.newFolder("update"), ".build.info")
        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET.copy(remoteVersion = "1.60.1.70000", remoteBuildConfig = "0".repeat(32)))

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET)

        assertEquals(2, buildInfo.readLines().size)
        assertEquals("1.60.1.70658", WowClientDownloader.readBuildInfo(buildInfo)?.get("Version"))
        assertEquals("165410aca770316ea0f88100bb4830c2", WowClientDownloader.readBuildInfo(buildInfo)?.get("Build Key"))
    }

    @Test
    fun readBuildInfo_detectsIncompatibleGameWhenForeverBetaMissingFromMultiGame() {
        val root = tempFolder.newFolder("multi_incompatible")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n" +
            "us|1|11111111111111111111111111111111|22222222222222222222222222222222|33333333333333333333333333333333|10000|tpr/wow|level3.blizzard.com|?|0|11.0.7.58238|wow\n" +
            "us|1|44444444444444444444444444444444|55555555555555555555555555555555|66666666666666666666666666666666|10000|tpr/wow|level3.blizzard.com|?|0|1.15.5.58238|wow_classic_era\n"
        )

        val result = WowClientDownloader.readBuildInfo(buildInfo)
        assertNotNull(result)
        assertEquals("wow", result?.get("Product"))
        val check = WowClientDownloader.checkVersion(root, "us")
        assertNull(check)
    }

    @Test
    fun readBuildInfo_parsesHostInstallationIfExists() {
        val hostFile = File("/Applications/World of Warcraft/.build.info")
        if (!hostFile.isFile) return
        val result = WowClientDownloader.readBuildInfo(hostFile)
        assertNotNull(result)
        assertNotNull(result?.get("Version"))
    }

    private companion object {
        val BETA_BUILD_INFO =
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Version!STRING:0|Product!STRING:0\n" +
                "eu|1|77777777777777777777777777777777|88888888888888888888888888888888|||tpr/wow|level3.blizzard.com|http://level3.blizzard.com/?maxhosts=8|OSX x86_64 EU? enUS speech?|1.15.9.70003|wow_classic_era\n" +
                "eu|1|99999999999999999999999999999999|88888888888888888888888888888888|||tpr/wow|level3.blizzard.com|http://level3.blizzard.com/?maxhosts=8|OSX x86_64 EU? enUS speech?|1.60.1.70205|wow_classic_beta\n"

        val TARGET = WowClientDownloader.VersionCheckResult(
            localVersion = "",
            remoteVersion = "1.60.1.70658",
            isOutdated = true,
            remoteBuildConfig = "165410aca770316ea0f88100bb4830c2",
            remoteCdnConfig = "f0fcf03baf658e38f2f6c96a77798a2c",
            remoteCdnHosts = listOf("level3.blizzard.com", "us.cdn.blizzard.com"),
            remoteCdnPath = "tpr/wow",
            remoteCdnServers = listOf("http://level3.blizzard.com/?maxhosts=8", "https://us.cdn.blizzard.com/?maxhosts=4&fallback=1"),
        )
    }

    @Test
    fun readBuildInfo_returnsNullOnMissingFile() {
        val nonExistent = File(tempFolder.root, "does_not_exist")
        assertNull(WowClientDownloader.readBuildInfo(nonExistent))
    }

    @Test
    fun rollbackTarget_reinstallsTheKnownBuildWithTheInstallsCdnSettings() {
        val root = tempFolder.newFolder("rollback")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Version!STRING:0|Product!STRING:0\n" +
                "cn|1|0acab1e6aa41b46165d2ca66853b417b|36baf7539c6f6c557bfe541f165a77b0|||tpr/wow|blzdist-wow.necdn.leihuo.netease.com|http://blzdist-wow.necdn.leihuo.netease.com/?maxhosts=6 https://blzdist-wow.necdn.leihuo.netease.com/?maxhosts=4&fallback=1|Windows arm64 CN? enUS speech?|1.60.1.71037|wowf\n"
        )

        val target = WowClientDownloader.rollbackTarget(buildInfo, "1.60.1.70959")
        assertNotNull(target)
        assertEquals("1.60.1.70959", target?.remoteVersion)
        assertEquals("ad887adcf63b8746155dd40e1930b80f", target?.remoteBuildConfig)
        assertEquals("96bea67d7d87c3dacb917be74097eaa6", target?.remoteCdnConfig)
        assertEquals(listOf("blzdist-wow.necdn.leihuo.netease.com"), target?.remoteCdnHosts)
        assertEquals("tpr/wow", target?.remoteCdnPath)
        // The install's own tags and branch stay; only the build moves.
        assertEquals("", target?.region)
        assertFalse(target!!.isOutdated)
        assertEquals("1.60.1.71037", target.localVersion)
    }

    @Test
    fun rollbackTarget_refusesUnknownBuildsAndTheInstalledOne() {
        val root = tempFolder.newFolder("rollback_refused")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Version!STRING:0|Product!STRING:0\n" +
                "cn|1|0acab1e6aa41b46165d2ca66853b417b|36baf7539c6f6c557bfe541f165a77b0|||tpr/wow|blzdist-wow.necdn.leihuo.netease.com||?|1.60.1.70959|wowf\n"
        )

        assertNull(WowClientDownloader.rollbackTarget(buildInfo, "1.60.1.71037"))
        // Already on it: no download, and the launcher must not "update" to the same build.
        assertNull(WowClientDownloader.rollbackTarget(buildInfo, "1.60.1.70959"))
        assertNull(WowClientDownloader.rollbackTarget(File(root, "missing"), "1.60.1.70959"))
    }
}

