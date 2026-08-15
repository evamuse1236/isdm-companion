package org.isdm.companion.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.isdm.companion.CompanionApplication
import org.isdm.companion.engine.Credentials
import org.json.JSONObject

class ReadingActivity : ComponentActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CompanionApplication
        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL)
        if (!isTrustedLmsUrl(sourceUrl)) {
            app.diagnostics.log("reading_open_rejected", mapOf("reason" to "untrusted_url"))
            finish()
            return
        }
        val trustedSourceUrl = requireNotNull(sourceUrl)
        val stored = app.credentialStore.load()
        if (stored == null) {
            app.diagnostics.log("reading_open_rejected", mapOf("reason" to "credentials_missing"))
            finish()
            return
        }
        lifecycleScope.launch {
            val cookieHeaders = runCatching {
                app.lmsAdapter.browserCookieHeaders(Credentials(stored.email, stored.password))
            }.getOrElse { error ->
                app.diagnostics.log("reading_cookie_setup_failed", error = error)
                finish()
                return@launch
            }
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieHeaders.forEach { header -> cookieManager.setCookie(trustedSourceUrl, header) }
            cookieManager.flush()
            app.diagnostics.log("reading_browser_opened")
            setContent {
                MaterialTheme(typography = companionTypography) {
                    ReadingBrowser(
                        sourceUrl = trustedSourceUrl,
                        onClose = ::finish,
                        onWebView = { webView = it },
                        onExternalUrl = {
                            if (!openExternalUrlSafely(this@ReadingActivity, it)) {
                                app.diagnostics.log("reading_external_open_failed")
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE_URL = "reading_source_url"
        private const val LMS_HOST = "lms.isdm.org.in"

        private fun isTrustedLmsUrl(value: String?): Boolean {
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
            return uri.scheme == "https" && uri.host.equals(LMS_HOST, ignoreCase = true)
        }
    }
}

internal fun openExternalUrlSafely(context: Context, value: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReadingBrowser(
    sourceUrl: String,
    onClose: () -> Unit,
    onWebView: (WebView) -> Unit,
    onExternalUrl: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Close") }
            Text("LMS reading", color = Color(0xFF15201F), fontWeight = FontWeight.Bold)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = ReadingWebViewClient(sourceUrl, onExternalUrl)
                    onWebView(this)
                    loadUrl(sourceUrl)
                }
            },
        )
    }
}

private class ReadingWebViewClient(
    sourceUrl: String,
    private val onExternalUrl: (String) -> Unit,
) : WebViewClient() {
    private val resourceId = readingResourceId(sourceUrl)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.scheme == "https" && uri.host.equals("lms.isdm.org.in", ignoreCase = true)) return false
        onExternalUrl(uri.toString())
        return true
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        val id = resourceId ?: return
        val resourceSelector = JSONObject.quote("resource_$id")
        view.evaluateJavascript(
            """
            (function() {
              const runKey = '__isdmCompanionOpenReading';
              if (window[runKey]) return;
              window[runKey] = true;
              let attempts = 0;
              const timer = setInterval(function() {
                const resource = document.getElementById($resourceSelector);
                if (resource) {
                  clearInterval(timer);
                  const fitViewer = function() {
                    const frame = document.getElementById('ebookview-frame');
                    if (!frame) return false;
                    const height = window.innerHeight + 'px';
                    [document.documentElement, document.body, frame.parentElement, frame].forEach(function(element) {
                      element.style.setProperty('height', height, 'important');
                    });
                    document.body.style.setProperty('margin', '0', 'important');
                    return frame.getBoundingClientRect().height > 0;
                  };
                  const fitTimer = setInterval(function() {
                    if (fitViewer()) clearInterval(fitTimer);
                  }, 250);
                  setTimeout(function() { clearInterval(fitTimer); }, 15000);
                  window.addEventListener('resize', fitViewer);
                  resource.click();
                  return;
                }
                if (attempts % 4 === 0) {
                  const readings = Array.from(document.querySelectorAll('a')).find(function(anchor) {
                    return anchor.textContent.includes('Course Readings');
                  });
                  if (readings) readings.click();
                }
                attempts += 1;
                if (attempts >= 60) clearInterval(timer);
              }, 250);
            })();
            """.trimIndent(),
            null,
        )
    }
}

internal fun readingResourceId(sourceUrl: String): String? = sourceUrl
    .toHttpUrlOrNull()
    ?.takeIf { it.host.equals("lms.isdm.org.in", ignoreCase = true) }
    ?.queryParameter("vid")
    ?.takeIf { it.matches(Regex("\\d+")) }
