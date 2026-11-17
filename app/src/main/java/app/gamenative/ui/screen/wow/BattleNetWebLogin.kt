package app.gamenative.ui.screen.wow

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The official Battle.net web login, shown in an in-app WebView. On success the page
 * redirects to http://localhost:0/?ST=<token>; we cancel that navigation and hand the
 * token to [onToken] without ever loading localhost, so the token never leaves the app.
 *
 * Replaces the old email/password dialog: NetEase CN accounts no longer support
 * password login, so the launcher must sign in through the web OAuth flow and keep the
 * resulting launch token.
 */
@Composable
fun BattleNetWebLoginDialog(
    region: GameRegion,
    onDismiss: () -> Unit,
    onToken: (BattleNetToken.Token) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxSize(0.95f),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Battle.net login",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    BattleNetWebView(region = region, onToken = onToken)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BattleNetWebView(region: GameRegion, onToken: (BattleNetToken.Token) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Keep the login session cookies so a later "Sign In Again" can reuse them.
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    private var consumed = false

                    /** Extracts the token from the login's localhost:0 redirect, once. */
                    private fun handle(url: Uri?): Boolean {
                        if (consumed || url == null) return false
                        // The login finishes by redirecting to http://localhost:0/?ST=<token>.
                        if (url.scheme == "http" && url.host == "localhost") {
                            val token = BattleNetToken.parse(url.getQueryParameter("ST"))
                            if (token != null) {
                                consumed = true
                                onToken(token)
                                return true // swallow the localhost navigation; never actually load it
                            }
                        }
                        return false
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        handle(request.url)

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        // Fallback: Chromium blocks port 0 with ERR_UNSAFE_PORT before it can load,
                        // so if the navigation slipped past shouldOverrideUrlLoading, still grab the token.
                        handle(request.url)
                    }
                }
                loadUrl(BattleNetLoginUrl.forRegion(region))
            }
        },
    )
}

/** The web login endpoint for each region. CN uses the NetEase account domain. */
object BattleNetLoginUrl {
    // WoW's program_id ("codename" in the Battle.net product catalog) is "WoW" for every
    // WoW flavor, including Forever; the product uid (wowf) is passed to the game via -uid.
    private const val CODE = "WoW"

    fun forRegion(region: GameRegion): String = when (region) {
        GameRegion.CN -> "https://account.battlenet.com.cn/login/en/?externalChallenge=login&app=$CODE"
        else -> "https://${region.name.lowercase()}.battle.net/login/en/?externalChallenge=login&app=$CODE"
    }
}
