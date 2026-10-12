package com.xr3ed.nontonanimeidxr

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.verifyApp

@CloudstreamPlugin
class NontonAnimeIDXRPlugin : Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE, BuildConfig.BYPASS_PASSWORD)
        NontonAnimeIDProvider.context = context

        // Register Activity Lifecycle Callbacks to track foreground Activity
        val app = context.applicationContext as? Application
        app?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                TurnstileInterceptor.currentActivity = activity
            }
            override fun onActivityStarted(activity: Activity) {
                TurnstileInterceptor.currentActivity = activity
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                TurnstileInterceptor.currentActivity = activity
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (TurnstileInterceptor.currentActivity === activity) {
                    TurnstileInterceptor.currentActivity = null
                }
            }
        })

        // Unpack initial Activity if context is already an Activity or ContextWrapper
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) {
                TurnstileInterceptor.currentActivity = ctx
                break
            }
            ctx = ctx.baseContext
        }
        if (ctx is Activity) {
            TurnstileInterceptor.currentActivity = ctx
        }

        registerMainAPI(NontonAnimeIDProvider())
        registerExtractorAPI(Nontonanimeid())
        registerExtractorAPI(EmbedKotakAnimeid())
        registerExtractorAPI(KotakAnimeidCom())
        registerExtractorAPI(KotakAnimeidLinkS1())
        registerExtractorAPI(KotakAnimeidLinkS2())
        registerExtractorAPI(Gdplayer())
        registerExtractorAPI(Kotaksb())
        registerExtractorAPI(Vidhidepre())
        registerExtractorAPI(Rpmvip())
    }
}

