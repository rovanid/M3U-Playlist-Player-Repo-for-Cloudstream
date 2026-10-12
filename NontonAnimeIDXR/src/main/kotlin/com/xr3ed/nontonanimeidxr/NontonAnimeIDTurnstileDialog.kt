package com.xr3ed.nontonanimeidxr

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class NontonAnimeIDTurnstileDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
        private const val POLL_TIMEOUT_MS  = 120_000L
    }

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null

    private val handler = Handler(Looper.getMainLooper())
    private var cookiesSaved = false
    private var pollElapsedMs = 0L
    private var initialClearance = ""

    private fun extractClearance(cookieStr: String): String {
        return cookieStr.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("cf_clearance=") }
            ?.substringAfter("cf_clearance=")
            .orEmpty()
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (cookiesSaved || !isAdded) return

            CookieManager.getInstance().flush()
            val cookieStr = TurnstileInterceptor.getCfCookies(targetUrl)
            val currentClearance = extractClearance(cookieStr)

            // Hanya anggap berhasil jika cf_clearance baru benar-benar sudah ada!
            val isCleared = currentClearance.isNotEmpty() &&
                (initialClearance.isEmpty() || currentClearance != initialClearance)

            if (isCleared) {
                saveCookiesAndDismiss()
            } else if (pollElapsedMs >= POLL_TIMEOUT_MS) {
                updateStatus("⏱️ Waktu verifikasi habis. Silakan tutup dan coba lagi.")
            } else {
                scheduleNextPoll()
            }
        }
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        updateStatus("⏳ Menunggu verifikasi Cloudflare... (${pollElapsedMs / 1000}s)")
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            peekHeight = android.view.WindowManager.LayoutParams.MATCH_PARENT
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        val bottomSheet = dialog?.findViewById<android.view.View>(
            com.google.android.material.R.id.design_bottom_sheet
        )
        bottomSheet?.layoutParams?.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        bottomSheet?.setBackgroundColor(Color.parseColor("#151624"))
        bottomSheet?.requestLayout()
    }

    private fun dp(px: Int): Int = (px * requireContext().resources.displayMetrics.density).toInt()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val screenH = requireContext().resources.displayMetrics.heightPixels
        val webViewHeight = (screenH * 0.70).toInt()
        val topPadding = (screenH * 0.08).toInt()

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), topPadding, dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#151624"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(TextView(requireContext()).apply {
            text = "🛡️ Verifikasi Cloudflare NontonAnimeID"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(6))
        })

        statusText = TextView(requireContext()).apply {
            text = "⏳ Menyiapkan verifikasi keamanan..."
            textSize = 13f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(statusText)

        root.addView(TextView(requireContext()).apply {
            text = "Silakan centang kotak verifikasi Cloudflare jika diminta. Jendela akan menutup otomatis setelah cf_clearance berhasil didapatkan."
            textSize = 11f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, dp(10))
        })

        progressBar = ProgressBar(
            requireContext(), null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4C5070"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10) }
        }
        root.addView(progressBar)

        val wvContainer = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                webViewHeight
            )
        }
        webView = buildWebView()
        wvContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                webViewHeight + dp(100)
            ).apply {
                topMargin = -dp(70)
            }
        )
        root.addView(wvContainer)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        TurnstileInterceptor.clearCfCookies(targetUrl)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            flush()
        }

        initialClearance = extractClearance(TurnstileInterceptor.getCfCookies(targetUrl))

        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(requireContext())
        wv.setBackgroundColor(Color.parseColor("#151624"))
        wv.visibility = View.INVISIBLE

        wv.settings.apply {
            userAgentString = TurnstileInterceptor.USER_AGENT
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            @Suppress("DEPRECATION")
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            loadsImagesAutomatically = true
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (!cookiesSaved) {
                    updateStatus("⏳ Mempersiapkan koneksi... $newProgress%")
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val reqUri = request?.url ?: return false
                val reqHost = reqUri.host?.lowercase() ?: ""
                val targetUriHost = try {
                    android.net.Uri.parse(targetUrl).host?.lowercase() ?: ""
                } catch (_: Exception) {
                    ""
                }

                if (reqHost.isEmpty() ||
                    reqHost == targetUriHost ||
                    reqHost.contains("nontonanimeid") ||
                    reqHost.contains("cloudflare")) {
                    return false
                }

                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                val css = """
                    var style = document.getElementById('cf-custom-style');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'cf-custom-style';
                        style.innerHTML = ' \
                            body, html { background: #151624 !important; } \
                            #logo, .logo, #zone-name, .zone-name { display: none !important; } \
                        ';
                        document.head.appendChild(style);
                    }
                """.trimIndent()
                view?.evaluateJavascript(css, null)

                if (cookiesSaved) return

                val currentHostCookies = TurnstileInterceptor.getCfCookies(targetUrl)
                val currentClearance = extractClearance(currentHostCookies)
                val pageTitle = view?.title.orEmpty().lowercase()
                val isChallenge = pageTitle.contains("just a moment") || url.orEmpty().contains("__cf_chl")

                val isCleared = currentClearance.isNotEmpty() &&
                    (initialClearance.isEmpty() || currentClearance != initialClearance)

                if (isCleared && !isChallenge) {
                    saveCookiesAndDismiss()
                    return
                }

                webView?.visibility = View.VISIBLE
                updateStatus("👉 Ketuk kotak centang verifikasi di bawah jika muncul.")
            }
        }

        return wv
    }

    private fun saveCookiesAndDismiss() {
        if (cookiesSaved) return
        cookiesSaved = true

        handler.removeCallbacks(cookiePollRunnable)

        webView?.stopLoading()
        webView?.visibility = View.GONE
        progressBar?.visibility = View.GONE

        updateStatus("✅ Verifikasi Cloudflare (cf_clearance) berhasil!")

        handler.postDelayed({
            if (isAdded) {
                onFinished?.invoke(true)
                dismissAllowingStateLoss()
            }
        }, 600)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (!cookiesSaved) {
            handler.removeCallbacks(cookiePollRunnable)
            onFinished?.invoke(false)
        }
    }

    private fun updateStatus(msg: String) {
        activity?.runOnUiThread {
            statusText?.text = msg
            if (msg.startsWith("✅")) {
                progressBar?.visibility = View.GONE
                statusText?.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                progressBar?.visibility = View.VISIBLE
                statusText?.setTextColor(Color.parseColor("#A0A0B0"))
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(cookiePollRunnable)
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }
}
