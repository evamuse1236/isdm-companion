package org.isdm.companion.platform

import android.webkit.CookieManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

fun clearLmsBrowserSession(onComplete: () -> Unit = {}) {
    val cookies = CookieManager.getInstance()
    cookies.removeAllCookies {
        cookies.flush()
        onComplete()
    }
}

suspend fun clearLmsBrowserSessionAndWait(): Boolean = suspendCancellableCoroutine { continuation ->
    runCatching {
        clearLmsBrowserSession {
            if (continuation.isActive) continuation.resume(true)
        }
    }.onFailure {
        if (continuation.isActive) continuation.resume(false)
    }
}
