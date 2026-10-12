package com.xr3ed.nontonanimeidxr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.fragment.app.FragmentActivity
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TurnstileInterceptor : Interceptor {

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val lock = Any()
        @Volatile var currentActivity: Activity? = null
        @Volatile private var isDialogActive = false
        @Volatile private var lastSuccessTime = 0L
        @Volatile private var currentCookies = ""
        private var currentLatch: CountDownLatch? = null

        fun getCfCookies(url: String): String {
            val cm = CookieManager.getInstance()
            val full = cm.getCookie(url).orEmpty()
            val base = runCatching {
                val uri = URI(url)
                val hostParts = uri.host.split(".")
                if (hostParts.size >= 2) {
                    val rootDomain = hostParts.takeLast(2).joinToString(".")
                    cm.getCookie("${uri.scheme}://$rootDomain").orEmpty()
                } else ""
            }.getOrDefault("")

            val map = mutableMapOf<String, String>()
            "$full; $base".split(";").forEach {
                val pair = it.trim().split("=", limit = 2)
                if (pair.size == 2 && pair[0].isNotBlank()) {
                    map[pair[0].trim()] = pair[1].trim()
                }
            }
            return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        fun clearCfCookies(url: String) {
            try {
                val cm = CookieManager.getInstance()
                val uri = runCatching { URI(url) }.getOrNull()
                val host = uri?.host ?: ""
                val rootDomain = if (host.contains(".")) host.split(".").takeLast(2).joinToString(".") else host

                cm.setCookie(url, "cf_clearance=; Max-Age=0; path=/; Secure")
                if (rootDomain.isNotBlank()) {
                    cm.setCookie("https://$rootDomain", "cf_clearance=; Max-Age=0; path=/; Secure")
                    cm.setCookie(".$rootDomain", "cf_clearance=; Max-Age=0; path=/; Secure")
                }
                cm.flush()
            } catch (_: Exception) {}
        }

        fun isChallenge(response: Response): Boolean {
            if (response.code != 403 && response.code != 503) return false
            val server = response.header("Server").orEmpty().lowercase()
            val cfMitigated = response.header("cf-mitigated").orEmpty().lowercase()
            if (cfMitigated == "challenge") return true
            val peek = runCatching { response.peekBody(1024 * 32).string().lowercase() }.getOrNull() ?: ""
            return peek.contains("challenge-platform") ||
                   peek.contains("cf-browser-verification") ||
                   peek.contains("just a moment...") ||
                   peek.contains("enable javascript and cookies to continue") ||
                   peek.contains("__cf_chl") ||
                   (server.contains("cloudflare") && peek.contains("turnstile"))
        }

        fun getValidActivity(): FragmentActivity? {
            val deadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() <= deadline) {
                val act = currentActivity ?: unwrapActivity(NontonAnimeIDProvider.context) ?: getReflectedActivity()
                if (act is FragmentActivity && !act.isFinishing && !act.isDestroyed) {
                    return act
                }
                try {
                    Thread.sleep(100)
                } catch (_: Exception) {}
            }
            return null
        }

        private fun unwrapActivity(context: Context?): Activity? {
            var ctx = context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) return ctx
                ctx = ctx.baseContext
            }
            return ctx as? Activity
        }

        private fun getReflectedActivity(): Activity? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
                val activityThread = currentActivityThreadMethod.invoke(null) ?: return null
                val mActivitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
                val activities = mActivitiesField.get(activityThread) as? Map<*, *> ?: return null
                for (record in activities.values) {
                    if (record == null) continue
                    for (f in record.javaClass.declaredFields) {
                        if (f.type == Activity::class.java || Activity::class.java.isAssignableFrom(f.type)) {
                            f.isAccessible = true
                            val act = f.get(record) as? Activity
                            if (act != null && !act.isFinishing && !act.isDestroyed) {
                                return act
                            }
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        var cookieHeader = currentCookies.ifBlank { getCfCookies(url) }

        val initialRequestBuilder = originalRequest.newBuilder()
            .header("User-Agent", USER_AGENT)
        if (cookieHeader.isNotBlank()) {
            initialRequestBuilder.header("Cookie", cookieHeader)
        }

        // Selalu coba kirim request terlebih dahulu!
        val initialResponse = try {
            chain.proceed(initialRequestBuilder.build())
        } catch (e: Exception) {
            null
        }

        if (initialResponse != null && !isChallenge(initialResponse)) {
            return initialResponse
        }

        initialResponse?.close()

        // Jika bukan domain NontonAnimeID (misal link extractor video), jangan tampilkan dialog Turnstile
        val isTargetDomain = url.contains("nontonanimeid", ignoreCase = true)
        if (!isTargetDomain) {
            val fallbackReq = originalRequest.newBuilder().header("User-Agent", USER_AGENT).build()
            return chain.proceed(fallbackReq)
        }

        // Hapus cookie usang jika terdeteksi challenge pada domain target
        synchronized(lock) {
            currentCookies = ""
            clearCfCookies(url)
        }

        var latchToWait: CountDownLatch? = null
        var isLeader = false

        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - lastSuccessTime < 25_000L && currentCookies.contains("cf_clearance=")) {
                val retryRequest = originalRequest.newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", currentCookies)
                    .build()
                val retryResp = chain.proceed(retryRequest)
                if (!isChallenge(retryResp)) {
                    return retryResp
                }
                retryResp.close()
                currentCookies = ""
                clearCfCookies(url)
            }

            if (isDialogActive) {
                latchToWait = currentLatch
            } else {
                isDialogActive = true
                currentLatch = CountDownLatch(1)
                latchToWait = currentLatch
                isLeader = true
            }
        }

        if (isLeader) {
            val activity = getValidActivity()
            if (activity != null) {
                Handler(Looper.getMainLooper()).post {
                    val dialog = NontonAnimeIDTurnstileDialog(url) { success ->
                        synchronized(lock) {
                            val freshCookies = getCfCookies(url)
                            if (success || freshCookies.contains("cf_clearance=")) {
                                lastSuccessTime = System.currentTimeMillis()
                                currentCookies = freshCookies
                            }
                            isDialogActive = false
                            currentLatch?.countDown()
                        }
                    }
                    dialog.show(activity.supportFragmentManager, "NontonAnimeIDTurnstileDialog")
                }
            } else {
                synchronized(lock) {
                    isDialogActive = false
                    currentLatch?.countDown()
                }
            }
        }

        try {
            latchToWait?.await(90, TimeUnit.SECONDS)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val finalCookies = currentCookies.ifBlank { getCfCookies(url) }
        val finalRequestBuilder = originalRequest.newBuilder()
            .header("User-Agent", USER_AGENT)
        if (finalCookies.isNotBlank()) {
            finalRequestBuilder.header("Cookie", finalCookies)
        }

        val finalResponse = chain.proceed(finalRequestBuilder.build())
        if (isChallenge(finalResponse)) {
            finalResponse.close()
            throw IOException("Cloudflare verification required")
        }
        return finalResponse
    }
}

