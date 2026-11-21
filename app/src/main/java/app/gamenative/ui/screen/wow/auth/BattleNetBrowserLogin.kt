package app.gamenative.ui.screen.wow.auth

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.GameRegion
import app.gamenative.ui.screen.wow.WowClientDownloader
import app.gamenative.utils.SharedCache
import com.winlator.container.Container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Opt-in browser authentication. Tokens never enter game files, arguments, preferences, or logs. */
internal object BattleNetBrowserLogin {
    private const val PREFS = "battle_net_browser_login"
    private const val ASSET = "wow-session-bridge.exe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableUi = MutableStateFlow<Ui>(Ui.Idle)
    val ui = mutableUi.asStateFlow()
    private var active: Attempt? = null
    private var lastLaunch: Launch? = null

    sealed interface Ui {
        data object Idle : Ui
        class Waiting(val text: String) : Ui
        // Deliberately no generated toString: a challenge URL is sensitive.
        class Browser(val url: String) : Ui
        class Failed(val text: String) : Ui
    }

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    /**
     * Signs out of Battle.net's sign-in page. Its cookies and web storage keep the last account signed in,
     * so without this the next browser login goes straight back into that account and asks for nothing.
     * This app's only WebView is the Battle.net page.
     */
    fun signOut() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }

    /**
     * Copies what the last launch left on the client's side into the shared cache, which survives reinstalls and
     * needs no root: the game's own logs and Config.wtf, the helper's stage log, and the launch-option keys.
     * The client only reports a rejected session in its own log and on screen, so this is the app's only view of
     * why the server dropped a credential that Battle.net had just issued.
     */
    fun exportClientState(context: Context, gameRoot: File, container: Container) {
        val target = File(SharedCache.dir(context, "logs"), "wow-client")
        val prefix = File(container.rootDir, ".wine")
        val config = File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/WTF/Config.wtf")
        diag(context, "export: ${target.path}")
        copyFiles(File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/Logs"), target, context)
        copyFile(config, target, context)
        copyFile(File(gameRoot, WowClientDownloader.BUILD_INFO), target, context)
        copyFile(File(prefix, "drive_c/wow-session/bridge.log"), target, context)
        copyFile(File(SharedCache.dir(context, "logs"), "last-run.log"), target, context)
        val reported = setOf("portal", "textLocale", "audioLocale", "agentUID", "accountName", "gxApi")
        runCatching { config.readLines() }.getOrNull()?.forEach { line ->
            val key = line.removePrefix("SET ").substringBefore(' ')
            if (line.startsWith("SET ") && key in reported) diag(context, "config: $line")
        }
        diag(context, "launch options: ${launchOptions(File(prefix, "user.reg"))}")
    }

    /** Runs after Wine prefix initialization. Container executable remains the game for window tracking. */
    @Synchronized
    fun start(context: Context, container: Container): String {
        cancel()
        val gameRoot = Container.drivesIterator(container.drives).asSequence()
            .firstOrNull { it[0].equals("G", ignoreCase = true) }?.let { File(it[1]) }
            ?: throw IOException("Cannot locate the game drive for browser login")
        val build = WowClientDownloader.readBuildInfo(File(gameRoot, WowClientDownloader.BUILD_INFO))
            ?.get("Version")?.substringAfterLast('.')?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IOException("Cannot read the installed game build for browser login")
        val region = GameRegion.current(context, gameRoot.path)
        exportClientState(context, gameRoot, container)
        lastLaunch = Launch(context, gameRoot, container)
        diag(context, "start: region=${region.name} build=$build")
        File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/login.txt").delete()
        File(gameRoot, "${WowClientDownloader.LEGACY_FLAVOR_DIR}/login.txt").delete()
        check(!File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/login.txt").exists()) {
            "Remove the old login.txt before using browser login"
        }

        val directory = File(container.rootDir, ".wine/drive_c/wow-session")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare browser login" }
        // The prefix is in Android's app-private directory, never shared game storage.
        val helper = File(directory, ASSET)
        context.assets.open(ASSET).use { input -> helper.outputStream().use { input.copyTo(it) } }
        val bridge = SessionBridge()
        val request = File(directory, "request-${UUID.randomUUID()}.txt")
        try {
            request.writeText(bridge.config, Charsets.US_ASCII)
            request.setReadable(false, false)
            request.setWritable(false, false)
            check(request.setReadable(true, true) && request.setWritable(true, true)) {
                "Cannot protect the browser login request"
            }
            val host = if (region == GameRegion.CN)
                "cn.actual.battlenet.com.cn"
            else
                "${region.name.lowercase()}.actual.battle.net"
            val attempt = Attempt(context, bridge, request, host, build)
            active = attempt
            mutableUi.value = Ui.Waiting("Starting Battle.net browser login…")
            // One deadline covers Wine startup, queues and browser interaction, below the helper's 15 minutes.
            attempt.watchdog = scope.launch {
                delay(10 * 60_000L)
                fail(attempt, "Browser login timed out. Please try again.")
            }
            attempt.job = scope.launch { run(attempt) }
            // The helper runs under `wine explorer /desktop=shell`. The explorer passes the
            // rest of the command line to CreateProcessW(NULL, cmdline), whose loader does NOT
            // strip quotes from the first token (find_exe_file fails on a quoted path). The
            // paths contain no spaces, so leave them unquoted.
            val command = "C:\\wow-session\\$ASSET C:\\wow-session\\${request.name} " +
                "${container.executablePath} -launcherlogin -uid ${WowClientDownloader.TARGET_PRODUCT} -d3d11"
            diag(context, "command: $command")
            return command
        } catch (error: Exception) {
            bridge.close()
            request.delete()
            throw error
        }
    }

    private suspend fun run(attempt: Attempt) {
        try {
            attempt.bridge.accept().use { peer ->
                val device = peer.device().also { attempt.device.set(it) }
                validateDevice(device)
                val url = withClient(attempt) { client ->
                    client.connect()
                    client.webChallenge(device, attempt.build)
                }
                publish(attempt, Ui.Browser(url))
                diag(attempt.context, "challenge: ${BrowserAuthUrl.displayOrigin(url) ?: "unexpected origin"}")
                val ticket = withTimeout(15 * 60_000L) { attempt.ticket.await() }
                diag(attempt.context, "ticket: ${ticket.size} bytes")
                try {
                    publish(attempt, Ui.Waiting("Completing Battle.net sign-in…"))
                    // A fresh connection avoids holding a BGS socket open while the user handles CAPTCHA or 2FA.
                    val login = withClient(attempt) { client ->
                        client.connect()
                        client.webChallenge(device, attempt.build)
                        client.verifyAndGenerateGameToken(ticket)
                    }
                    // A sole account is passed as the game account; with several (or an unreadable list) the game
                    // asks, as the reference implementation does, instead of guessing a licence.
                    val account = login.accounts.singleOrNull()
                    diag(attempt.context, "token: ${login.token.size} bytes, accounts: ${login.accounts.size}" +
                        (account?.let { " region=${it.region}" } ?: ""))
                    try {
                        publish(attempt, Ui.Waiting("Starting WoW with the Battle.net session…"))
                        peer.launch(login.token, account)
                        diag(attempt.context, "helper: STARTED")
                    } finally {
                        login.token.fill(0)
                    }
                } finally {
                    ticket.fill(0)
                    device.fill(0)
                }
                publish(attempt, Ui.Idle)
            }
        } catch (_: TimeoutCancellationException) {
            fail(attempt, "Browser login timed out. Please try again.")
        } catch (_: CancellationException) {
            // User cancellation does not turn into a password-login attempt.
        } catch (error: BgsException) {
            fail(attempt, error.message ?: "Battle.net sign-in failed.")
        } catch (error: Exception) {
            // Never expose provider payloads, redirect URLs, credentials or arbitrary peer output.
            // A JSON parse detail, our own validation message, or the exception class makes the step identifiable.
            val detail = when (error) {
                is JSONException -> " ${error.message}"
                is IllegalArgumentException -> " ${error.message}"
                else -> " (${error.javaClass.simpleName})"
            }
            fail(attempt, "Browser sign-in could not start the game$detail. You can retry.")
        } finally {
            attempt.close()
            synchronized(this) { if (active === attempt) active = null }
        }
    }

    private fun <T> withClient(attempt: Attempt, action: (BgsClient) -> T): T {
        // The ARM64 helper reports the real Windows-on-ARM context, the same platform as the ARM64 game client.
        val client = BgsClient(attempt.host, platform = "WinA")
        attempt.client.set(client)
        try {
            if (attempt.cancelled) throw CancellationException()
            return action(client)
        } finally {
            attempt.client.compareAndSet(client, null)
            client.close()
        }
    }

    private fun validateDevice(device: ByteArray) {
        val expected = setOf("RGKY", "CPGE", "ULNG", "SLNG", "CNME", "UNME", "UTCO", "CARC", "CREV", "CLVL",
            "PMEM", "PSZE", "OVER", "CVRA", "CFTC", "CFTD", "CEFC", "CEFD", "CBRD", "CVEN", "ANME", "ADSC", "MAC")
        val json = JSONObject(device.toString(Charsets.UTF_8))
        check(json.keys().asSequence().toSet() == expected) { "Invalid device description" }
        expected.forEach { check(json.getLong(it) in 0..0xffff_ffffL) { "Invalid device description" } }
    }

    @Synchronized
    private fun publish(attempt: Attempt, state: Ui) {
        if (active === attempt && !attempt.cancelled) mutableUi.value = state
    }

    @Synchronized
    fun submit(browser: Ui.Browser, ticket: ByteArray) {
        val attempt = active
        if (mutableUi.value !== browser || attempt == null || attempt.ticket.isCompleted) {
            ticket.fill(0)
        } else {
            attempt.ticketBytes.set(ticket)
            attempt.ticket.complete(ticket)
        }
    }

    @Synchronized
    fun cancel(preserveFailure: Boolean = false) {
        active?.let { attempt ->
            attempt.cancelled = true
            attempt.close()
            attempt.job?.cancel()
        }
        active = null
        if (!preserveFailure || mutableUi.value !is Ui.Failed) mutableUi.value = Ui.Idle
    }

    @Synchronized
    fun abort(state: Ui) {
        if (mutableUi.value !== state) return
        cancel()
        // Closing the control socket alone cannot stop a helper that has already received the token.
        PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
    }

    @Synchronized
    fun webError(browser: Ui.Browser, message: String) {
        if (mutableUi.value === browser) active?.let { fail(it, message) }
    }

    @Synchronized
    private fun fail(attempt: Attempt, message: String) {
        if (active !== attempt || attempt.cancelled || mutableUi.value is Ui.Idle) return
        diag(attempt.context, "failed: $message")
        val failure = Ui.Failed(message)
        mutableUi.value = failure
        cancel(preserveFailure = true)
        scope.launch(Dispatchers.Main.immediate) {
            synchronized(this@BattleNetBrowserLogin) {
                // A queued failure must not tear down a newer attempt or a game the user has since launched.
                if (mutableUi.value === failure) PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
            }
        }
    }

    @Synchronized
    fun guestExited() {
        // The game has released its logs by now; the next launch would otherwise overwrite them before the export.
        lastLaunch?.let { exportClientState(it.context, it.gameRoot, it.container) }
        lastLaunch = null
        val attempt = active
        if (attempt != null && mutableUi.value !is Ui.Idle && mutableUi.value !is Ui.Failed) {
            val log = attempt.bridgeLog().trim()
            mutableUi.value = Ui.Failed(if (log.isEmpty())
                "The Wine login helper stopped before sign-in completed."
                else "The Wine login helper stopped before sign-in completed.\n\nHelper log:\n$log")
        }
        cancel(preserveFailure = true)
    }

    /** Appends one line to the shared-cache login log; never records page content, cookies or credentials. */
    private fun diag(context: Context, message: String) {
        try {
            val dir = SharedCache.dir(context, "logs")
            dir.mkdirs()
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            File(dir, "browser-login.log").appendText("$stamp $message\n")
        } catch (_: Throwable) {
            // Diagnostics must never break sign-in.
        }
    }

    private fun copyFiles(from: File, to: File, context: Context) {
        val files = from.listFiles() ?: return
        to.mkdirs()
        files.filter { it.isFile }.forEach { copyFile(it, to, context) }
    }

    private fun copyFile(file: File, to: File, context: Context) {
        if (!file.isFile || file.length() !in 1..MAX_COPY_BYTES) return
        to.mkdirs()
        runCatching { file.copyTo(File(to, file.name), overwrite = true) }
            .onFailure { diag(context, "copy ${file.name}: ${it.javaClass.simpleName}") }
    }

    /** The launch-option values the client reads; sizes and timestamps only, never the protected token. */
    private fun launchOptions(reg: File): String {
        val lines = runCatching { reg.takeIf { it.isFile }?.readLines() }.getOrNull()
            ?: return "user.reg unreadable"
        val found = mutableListOf<String>()
        var inside = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inside = trimmed.lowercase(Locale.ROOT)
                    .startsWith("[software\\\\blizzard entertainment\\\\battle.net\\\\launch options")
                continue
            }
            if (!inside || trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val name = trimmed.substringBefore('=').trim('"')
            val value = trimmed.substringAfter('=', "")
            when (name) {
                "WEB_TOKEN" -> found += "WEB_TOKEN=${value.replace("\\", "").length / 2}B"
                "ACCOUNT_TS", "GAME_ACCOUNT", "REGION" -> found += "$name=${value.trim('"')}"
            }
        }
        return if (found.isEmpty()) "none" else found.joinToString(" ")
    }

    private const val MAX_COPY_BYTES = 4L * 1024 * 1024

    private class Launch(val context: Context, val gameRoot: File, val container: Container)

    private class Attempt(
        val context: Context,
        val bridge: SessionBridge,
        val request: File,
        val host: String,
        val build: Int,
    ) {
        val ticket = CompletableDeferred<ByteArray>()
        val client = AtomicReference<BgsClient?>()
        val ticketBytes = AtomicReference<ByteArray?>()
        val device = AtomicReference<ByteArray?>()
        @Volatile var cancelled = false
        var job: Job? = null
        var watchdog: Job? = null
        fun bridgeLog(): String {
            val log = File(request.parentFile, "bridge.log")
            if (!log.exists() || !log.canRead()) return ""
            return runCatching { log.readText() }.getOrDefault("")
        }
        fun close() {
            runCatching { client.getAndSet(null)?.close() }
            runCatching { bridge.close() }
            watchdog?.cancel()
            ticketBytes.getAndSet(null)?.fill(0)
            device.getAndSet(null)?.fill(0)
            request.delete()
        }
    }
}

@Composable
internal fun BattleNetBrowserLoginHost() {
    val state by BattleNetBrowserLogin.ui.collectAsStateWithLifecycle()
    when (val current = state) {
        BattleNetBrowserLogin.Ui.Idle -> Unit
        is BattleNetBrowserLogin.Ui.Browser -> BattleNetWebDialog(
            url = current.url,
            onTicket = { BattleNetBrowserLogin.submit(current, it) },
            onCancel = { BattleNetBrowserLogin.abort(current) },
            onError = { BattleNetBrowserLogin.webError(current, it) },
        )
        else -> AlertDialog(
            onDismissRequest = { BattleNetBrowserLogin.abort(current) },
            title = { Text("Battle.net browser login") },
            text = { Text(when (current) {
                is BattleNetBrowserLogin.Ui.Waiting -> current.text
                is BattleNetBrowserLogin.Ui.Failed -> current.text
                else -> ""
            }) },
            confirmButton = {
                TextButton(onClick = { BattleNetBrowserLogin.abort(current) }) {
                    Text(if (current is BattleNetBrowserLogin.Ui.Failed) "Close" else "Cancel")
                }
            },
        )
    }
}
