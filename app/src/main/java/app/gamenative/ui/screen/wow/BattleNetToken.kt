package app.gamenative.ui.screen.wow

import android.content.Context
import android.util.Base64
import app.gamenative.Crypto
import timber.log.Timber

/**
 * A Battle.net launch token, handed out by the official web login
 * (https://<region>.battle.net/login/en/?externalChallenge=login&app=WoW;
 * CN uses https://account.battlenet.com.cn/...) and delivered to the launcher
 * via a redirect to http://localhost:0/?ST=<token>.
 *
 * Format: <REGION>-<32 hex>-<account id>, e.g. CN-0123456789abcdef0123456789abcdef-1234567890.
 * The token stays valid for months, so it is cached encrypted on-device (Android
 * Keystore, the same [Crypto] the old email/password login used) and reused across
 * launches until Battle.net rejects it.
 */
object BattleNetToken {
    private val FORMAT = Regex("^[A-Z]{2}-[0-9a-fA-F]{32}-[0-9]+$")

    data class Token(val value: String) {
        val region: String get() = value.substringBefore('-')
        val accountId: String get() = value.substringAfterLast('-')
    }

    /** Parses a raw token from the login redirect's `ST` query parameter. */
    fun parse(raw: String?): Token? =
        raw?.trim()?.takeIf { FORMAT.matches(it) }?.let(::Token)

    fun load(context: Context): Token? {
        val sealed = prefs(context).getString(KEY_TOKEN, null) ?: return null
        return try {
            Token(String(Crypto.decrypt(Base64.decode(sealed, Base64.NO_WRAP))))
        } catch (e: Exception) {
            Timber.w(e, "Saved Battle.net token unreadable, clearing it")
            forget(context)
            null
        }
    }

    fun save(context: Context, token: Token) {
        val sealed = Base64.encodeToString(Crypto.encrypt(token.value.toByteArray()), Base64.NO_WRAP)
        prefs(context).edit().putString(KEY_TOKEN, sealed).apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "battle_net_token"
    private const val KEY_TOKEN = "token"
}
