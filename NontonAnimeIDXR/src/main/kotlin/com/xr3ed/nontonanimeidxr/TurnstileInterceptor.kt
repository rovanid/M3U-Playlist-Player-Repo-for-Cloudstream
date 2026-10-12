package com.xr3ed.nontonanimeidxr

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.fragment.app.FragmentActivity
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TurnstileInterceptor : Interceptor {

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val lock = Any()
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

        fun isChallenge(response: Response): Boolean {
            if (response.code == 403 || response.code == 503) return true
            val peek = runCatching { response.peekBody(1024 * 32).string().lowercase() }.getOrNull() ?: ""
            return peek.contains("challenge-platform") ||
                   peek.contains("cf-browser-verification") ||
                   peek.contains("just a moment...") ||
                   peek.contains("enable javascript and cookies to continue") ||
                   peek.contains("__cf_chl")
        }
    }

    private fun getResumedActivity(): android.app.Activity? {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
            val activityThread = currentActivityThreadMethod.invoke(null) ?: return null
            val mActivitiesField = activityThreadClass.getDeclaredField("mActivities")
            mActivitiesField.isAccessible = true
            val activities = mActivitiesField.get(activityThread) as? Map<*, *> ?: return null
            for (activityRecord in activities.values) {
                if (activityRecord == null) continue
                val pausedField = activityRecord.javaClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                val paused = pausedField.get(activityRecord) as? Boolean ?: true
                if (!paused) {
                    val activityField = activityRecord.javaClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    val activity = activityField.get(activityRecord) as? android.app.Activity
                    if (activity != null && !activity.isFinishing) {
                        return activity
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        var cookieHeader = currentCookies.ifBlank { getCfCookies(url) }

        // Jika sama sekali belum punya cf_clearance, jangan buang waktu request yang pasti diblokir
        val hasClearance = cookieHeader.contains("cf_clearance=")

        val initialRequestBuilder = originalRequest.newBuilder()
            .header("User-Agent", USER_AGENT)
        if (cookieHeader.isNotBlank()) {
            initialRequestBuilder.header("Cookie", cookieHeader)
        }

        val response = if (hasClearance) {
            chain.proceed(initialRequestBuilder.build())
        } else {
            null
        }

        // Cek apakah respon valid dan bukan challenge
        if (response != null && !isChallenge(response)) {
            return response
        }

        response?.close()

        var needShowDialog = false
        var latchToWait: CountDownLatch? = null

        synchronized(lock) {
            val now = System.currentTimeMillis()
            // Jika baru berhasil diverifikasi dalam 20 detik terakhir dan punya clearance, retry
            if (now - lastSuccessTime < 20_000L && currentCookies.contains("cf_clearance=")) {
                val retryRequest = originalRequest.newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", currentCookies)
                    .build()
                val retryResp = chain.proceed(retryRequest)
                if (!isChallenge(retryResp)) {
                    return retryResp
                }
                retryResp.close()
            }

            if (isDialogActive) {
                latchToWait = currentLatch
            } else {
                isDialogActive = true
                currentLatch = CountDownLatch(1)
                latchToWait = currentLatch
                needShowDialog = true
            }
        }

        if (needShowDialog) {
            val resumedActivity = getResumedActivity() as? FragmentActivity
            if (resumedActivity != null) {
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
                    dialog.show(resumedActivity.supportFragmentManager, "NontonAnimeIDTurnstileDialog")
                }
            } else {
                synchronized(lock) {
                    isDialogActive = false
                    currentLatch?.countDown()
                }
            }
        }

        try {
            latchToWait?.await(60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val finalCookies = currentCookies.ifBlank { getCfCookies(url) }
        val finalRequestBuilder = originalRequest.newBuilder()
            .header("User-Agent", USER_AGENT)
        if (finalCookies.isNotBlank()) {
            finalRequestBuilder.header("Cookie", finalCookies)
        }

        return chain.proceed(finalRequestBuilder.build())
    }
}
