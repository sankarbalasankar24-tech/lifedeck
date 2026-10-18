package com.lifedeck.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.webkit.WebViewAssetLoader
import com.lifedeck.app.service.FloatingBubbleService

/**
 * High-fidelity native host delivering pixel-for-pixel parity with the studio preview.
 * Loads optimized, offline local assets via Google WebViewAssetLoader with zero network latency.
 * Robust navigation handling ensures no crashes or accidental closures on tab or button clicks.
 */
class MainActivity : ComponentActivity() {

    data class DeepLink(val taskId: String? = null, val screen: String? = null, val sharedText: String? = null)

    object IntentExtra {
        const val SHARE_TEXT = "android.intent.extra.TEXT"
        const val OPEN_TASK_ID = "com.lifedeck.app.OPEN_TASK_ID"
        const val OPEN_SCREEN = "com.lifedeck.app.OPEN_SCREEN"
    }

    companion object {
        val deepLinks = kotlinx.coroutines.flow.MutableSharedFlow<DeepLink>(replay = 1, extraBufferCapacity = 4)
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.parseColor("#0E1514"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith("https://appassets.androidplatform.net") || url.startsWith("file://")) {
                        return false // stay in webview
                    }
                    return try {
                        val intent = Intent(Intent.ACTION_VIEW, request.url)
                        view.context.startActivity(intent)
                        true
                    } catch (e: Exception) {
                        true
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    android.util.Log.w("LifeDeckWeb", "Web error: ${error.description}")
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    android.util.Log.d("LifeDeckWeb", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    return true
                }
            }

            addJavascriptInterface(LifeDeckBridge(this@MainActivity), "LifeDeckNative")
        }

        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.loadUrl("https://appassets.androidplatform.net/assets/dist/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("OPEN_QUICK_ADD", false)) {
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('lifedeck:quickadd'))", null)
        }
    }

    class LifeDeckBridge(private val context: Context) {
        @JavascriptInterface
        fun toggleFloatingBubble(enabled: Boolean) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (enabled) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}
