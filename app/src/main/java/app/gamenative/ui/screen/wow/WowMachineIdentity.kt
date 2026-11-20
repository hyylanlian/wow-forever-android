package app.gamenative.ui.screen.wow

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import app.gamenative.Crypto
import com.winlator.core.WineRegistryEditor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.UUID
import timber.log.Timber

/**
 * The Windows identity this install shows Battle.net. The bundled prefix ships one fixed MachineGuid and install date,
 * so every player looked like the same PC, and the computer name was the phone's IP address, which changes with every
 * network. Battle.net's fraud checks reject logins like that with BLZ51914400 (#57).
 *
 * Generated once per install and kept in app storage, so it stays the same across launches and app updates. The
 * ProductId is left as is: it's also baked into DigitalProductId, and OEM installs share it anyway.
 */
internal data class WowMachineIdentity(
    val machineGuid: String,
    val computerName: String,
    val installDate: Int,
    val volumeSerial: String,
) {
    /** Writes the identity into the Wine prefix at [prefixDir] (the container's `.wine`), unless it's already there. */
    fun applyTo(prefixDir: File) {
        val systemReg = File(prefixDir, "system.reg")
        if (!systemReg.isFile) {
            Timber.w("No system.reg in $prefixDir, skipping the machine identity")
            return
        }
        // The editor copies and rescans the whole multi-MB file for every value, so it only runs when something differs.
        val applied = listOf(
            "\"MachineGuid\"=\"$machineGuid\"",
            "\"InstallDate\"=dword:%08x".format(installDate),
            "\"ComputerName\"=\"$computerName\"",
            "\"VendorIdentifier\"=\"Qualcomm\"",
            "\"ProcessorNameString\"=\"Qualcomm Oryon\"",
        )
        if (!systemReg.readText().let { reg -> applied.all { it in reg } }) {
            WineRegistryEditor(systemReg).use { registry ->
                registry.setStringValue("Software\\Microsoft\\Cryptography", "MachineGuid", machineGuid)
                CURRENT_VERSION_KEYS.forEach { registry.setDwordValue(it, "InstallDate", installDate) }
                // wineboot also sets these on boot, from gethostname(), which the DNS shim answers with the same name.
                registry.setStringValue(COMPUTER_NAME_KEY, "ComputerName", computerName)
                registry.setStringValue(TCPIP_KEY, "Hostname", computerName)
                registry.setStringValue(TCPIP_KEY, "NV Hostname", computerName)
                // The game's anti-tamper hashes the CPU identity; Wine leaves the ARM64 vendor/name blank, which the
                // client reports as "vendor: ?" and then stalls on. Report the Snapdragon Oryon the device runs.
                registry.setStringValue(CPU_KEY, "VendorIdentifier", "Qualcomm")
                registry.setStringValue(CPU_KEY, "ProcessorNameString", "Qualcomm Oryon")
            }
        }
        // Wine's mount manager reads C:'s volume serial from this file.
        val serialFile = File(prefixDir, "drive_c/.windows-serial")
        if (serialFile.parentFile?.isDirectory == true && serialFile.takeIf { it.isFile }?.readText()?.trim() != volumeSerial) {
            serialFile.writeText("$volumeSerial\n")
        }
    }

    companion object {
        /** The env var the DNS shim reads the computer name from. */
        const val HOSTNAME_ENV = "GAMENATIVE_HOSTNAME"

        private const val FILE_NAME = "wow_identity.properties"
        private const val SECRET_FILE = "wow_identity.dat"
        private const val FORMAT_VERSION = "v2"
        private const val FINGERPRINT_KEY = "fingerprint"
        private val CURRENT_VERSION_KEYS = listOf(
            "Software\\Microsoft\\Windows NT\\CurrentVersion",
            "Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion",
        )
        private const val COMPUTER_NAME_KEY = "System\\ControlSet001\\Control\\ComputerName\\ComputerName"
        private const val TCPIP_KEY = "System\\ControlSet001\\Services\\Tcpip\\Parameters"
        private const val CPU_KEY = "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0"
        private const val NAME_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        // 2021-01-01 to 2025-06-01: a plausible Windows 10/11 install date.
        private const val INSTALL_DATE_FROM = 1_609_459_200
        private const val INSTALL_DATE_UNTIL = 1_748_736_000

        // Also keeps the env var and the registry in agreement within a run if the identity can't be saved.
        private var cached: WowMachineIdentity? = null

        @Synchronized
        fun load(context: Context): WowMachineIdentity = cached ?: read(context).also { cached = it }

        /** Generates a fresh identity. Battle.net then sees this install as a new PC. */
        @Synchronized
        fun reset(context: Context): WowMachineIdentity {
            cached = null
            runCatching { File(context.filesDir, SECRET_FILE).delete() }
            runCatching { File(context.filesDir, FILE_NAME).delete() }
            return load(context)
        }

        private fun read(context: Context): WowMachineIdentity {
            val file = File(context.filesDir, SECRET_FILE)
            decryptFrom(file)?.let { (identity, fingerprint) ->
                if (fingerprint == deviceFingerprint(context)) return identity
                // A backup restored onto a different phone must not share this PC identity.
                Timber.w("Machine identity belongs to another device; regenerating")
                file.delete()
            }
            // One-time migration of the legacy plaintext identity so app updates keep the same PC.
            val legacy = File(context.filesDir, FILE_NAME)
            runCatching {
                val props = Properties()
                legacy.inputStream().use(props::load)
                fromProperties(props)
            }.getOrNull()?.let { identity ->
                saveEncrypted(context, file, identity)
                legacy.delete()
                return identity
            }
            val identity = generate(SecureRandom())
            saveEncrypted(context, file, identity)
            return identity
        }

        /** Stable across app updates (same signing key), different per device and per signing key. */
        private fun deviceFingerprint(context: Context): String {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "none"
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL ?: "" else ""
            return sha256Hex("$FORMAT_VERSION|$androidId|$soc|${Build.HARDWARE}|${Build.MODEL}")
        }

        private fun saveEncrypted(context: Context, file: File, identity: WowMachineIdentity) {
            val props = Properties()
            props.setProperty("version", FORMAT_VERSION)
            props.setProperty(FINGERPRINT_KEY, deviceFingerprint(context))
            props.putAll(identity.toProperties())
            val text = ByteArrayOutputStream().use { output ->
                props.store(output, null)
                output.toByteArray()
            }
            val sealed = Base64.encodeToString(Crypto.encrypt(text), Base64.NO_WRAP)
            runCatching { file.writeText(sealed) }
                .onFailure { Timber.e(it, "Couldn't save the machine identity") }
        }

        private fun decryptFrom(file: File): Pair<WowMachineIdentity, String>? = runCatching {
            val sealed = Base64.decode(file.readText().trim(), Base64.NO_WRAP)
            val props = Properties()
            ByteArrayInputStream(Crypto.decrypt(sealed)).use(props::load)
            check(props.getProperty("version") == FORMAT_VERSION)
            val identity = fromProperties(props) ?: error("malformed identity")
            identity to (props.getProperty(FINGERPRINT_KEY) ?: "")
        }.getOrNull()

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun generate(random: java.util.Random): WowMachineIdentity {
            // A random (version 4) UUID, like Windows generates.
            val machineGuid = UUID(random.nextLong() and 0xF000L.inv() or 0x4000L, random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL or Long.MIN_VALUE)
            return WowMachineIdentity(
                machineGuid = machineGuid.toString(),
                computerName = "DESKTOP-" + (1..7).map { NAME_CHARS[random.nextInt(NAME_CHARS.length)] }.joinToString(""),
                installDate = INSTALL_DATE_FROM + random.nextInt(INSTALL_DATE_UNTIL - INSTALL_DATE_FROM),
                volumeSerial = "%08x".format(random.nextInt()),
            )
        }

        private fun fromProperties(props: Properties): WowMachineIdentity? = runCatching {
            WowMachineIdentity(
                machineGuid = props.getProperty("machineGuid")!!,
                computerName = props.getProperty("computerName")!!,
                installDate = props.getProperty("installDate")!!.toInt(),
                volumeSerial = props.getProperty("volumeSerial")!!,
            )
        }.getOrNull()

        private fun WowMachineIdentity.toProperties() = mapOf(
            "machineGuid" to machineGuid,
            "computerName" to computerName,
            "installDate" to installDate.toString(),
            "volumeSerial" to volumeSerial,
        )
    }
}
