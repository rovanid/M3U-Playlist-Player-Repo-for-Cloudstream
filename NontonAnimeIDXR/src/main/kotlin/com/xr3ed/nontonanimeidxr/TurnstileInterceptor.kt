package com.xr3ed.nontonanimeidxr

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.fragment.app.FragmentActivity
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TurnstileInterceptor(private val targetCookie: String = "cf_clearance") : Interceptor {

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val lock = Any()
        @Volatile private var isDialogActive = false
        @Volatile private var lastSuccessTime = 0L
        @Volatile private var currentCookies = ""
        private var currentLatch: CountDownLatch? = null
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
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()

        cookieManager.setAcceptCookie(true)

        // Gunakan cookies yang tersimpan di memori atau CookieManager
        val initialCookie = currentCookies.ifBlank { cookieManager.getCookie(domainUrl) ?: "" }

        val requestBuilder = originalRequest.newBuilder()
            .header("User-Agent", USER_AGENT)
        if (initialCookie.isNotBlank()) {
            requestBuilder.header("Cookie", initialCookie)
        }

        val initialResponse = chain.proceed(requestBuilder.build())
        if (initialResponse.code != 403 && initialResponse.code != 503) {
            return initialResponse
        }

        initialResponse.close()

        var needShowDialog = false
        var latchToWait: CountDownLatch? = null

        synchronized(lock) {
            val now = System.currentTimeMillis()
            // Jika baru saja berhasil diverifikasi dalam 30 detik terakhir, langsung pakai cookie terbaru
            if (now - lastSuccessTime < 30_000L && currentCookies.isNotBlank()) {
                val retryRequest = originalRequest.newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", currentCookies)
                    .build()
                return chain.proceed(retryRequest)
            }

            if (isDialogActive) {
                // Dialog sudah dibuka oleh request lain, request ini cukup menunggu latch yang sama
                latchToWait = currentLatch
            } else {
                // Request ini menjadi leader yang membuka dialog
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
                    val dialog = NontonAnimeIDTurnstileDialog(url, targetCookie) { success ->
                        synchronized(lock) {
                            if (success) {
                                lastSuccessTime = System.currentTimeMillis()
                                currentCookies = CookieManager.getInstance().getCookie(domainUrl) ?: ""
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

        // Tunggu hingga dialog selesai diverifikasi
        try {
            latchToWait?.await(60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val finalCookies = currentCookies.ifBlank { cookieManager.getCookie(domainUrl) ?: "" }
        val finalRequestBuilder = originalRequest.newBuilder()
            .header("User-Agent", USER_AGENT)
        if (finalCookies.isNotBlank()) {
            finalRequestBuilder.header("Cookie", finalCookies)
        }

        return chain.proceed(finalRequestBuilder.build())
    }
}
