package io.github.douyinextractor.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** WebView's cookie jar is process-wide. Keep the lease through asynchronous close cleanup. */
internal object BrowserSessionData {
    private val lease = Mutex()

    suspend fun useSession(block: suspend () -> Unit) = lease.withLock {
        try {
            withContext(NonCancellable) { clear() }
            block()
        } finally {
            withContext(NonCancellable) { clear() }
        }
    }

    // Called on Main. Do not cancel an in-progress CookieManager deletion and release the lease:
    // its callback could otherwise arrive after a newer browser has already started loading.
    private suspend fun clear() {
        WebStorage.getInstance().deleteAllData()
        val cookies = CookieManager.getInstance()
        suspendCoroutine { continuation ->
            cookies.removeAllCookies { continuation.resume(Unit) }
        }
        cookies.flush()
    }
}
