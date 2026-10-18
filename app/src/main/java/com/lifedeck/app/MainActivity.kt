package com.lifedeck.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.AlarmManagerCompat
import androidx.webkit.WebViewAssetLoader
import com.lifedeck.app.notify.ReminderReceiver
import com.lifedeck.app.service.FloatingBubbleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * High-fidelity native host delivering pixel-for-pixel parity with the studio preview.
 * Loads optimized, offline local assets via Google WebViewAssetLoader with zero network latency.
 * Provides bridge hooks for speech recognition, system sound selection, background Google Sheets sync,
 * AlarmManager exact alarms, and always-on floating quick-entry bubble.
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
    private var pendingSoundIsAlarm = false

    // Launcher for speech-to-text recognition
    private val voiceInputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull() ?: ""
            webView.post {
                webView.evaluateJavascript("window.onVoiceInputResult && window.onVoiceInputResult(${JSONObject.quote(text)});", null)
            }
        } else {
            webView.post {
                webView.evaluateJavascript("window.onVoiceInputResult && window.onVoiceInputResult('');", null)
            }
        }
    }

    // Launcher for system sound selection (Alarm & Notification ringtones)
    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val title = if (uri != null) {
                val ringtone = RingtoneManager.getRingtone(this, uri)
                ringtone?.getTitle(this) ?: "Selected Sound"
            } else {
                "Silent"
            }
            val uriStr = uri?.toString() ?: ""
            val prefs = getSharedPreferences("lifedeck_prefs", Context.MODE_PRIVATE)

            if (pendingSoundIsAlarm) {
                prefs.edit()
                    .putString("alarm_sound_uri", uriStr)
                    .putString("alarm_sound_title", title)
                    .apply()
                webView.post {
                    webView.evaluateJavascript(
                        "window.onSystemSoundPicked && window.onSystemSoundPicked('alarm', ${JSONObject.quote(title)}, ${JSONObject.quote(uriStr)});",
                        null
                    )
                }
            } else {
                prefs.edit()
                    .putString("notification_sound_uri", uriStr)
                    .putString("notification_sound_title", title)
                    .apply()
                webView.post {
                    webView.evaluateJavascript(
                        "window.onSystemSoundPicked && window.onSystemSoundPicked('notification', ${JSONObject.quote(title)}, ${JSONObject.quote(uriStr)});",
                        null
                    )
                }
            }
        }
    }

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
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (intent.getBooleanExtra("OPEN_QUICK_ADD", false)) {
                        view?.postDelayed({
                            view.evaluateJavascript("window.dispatchEvent(new CustomEvent('lifedeck:quickadd'))", null)
                        }, 200)
                    }
                }

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

                override fun onPermissionRequest(request: PermissionRequest) {
                    // Grant microphone access if requested inside WebView
                    request.grant(request.resources)
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

    override fun onResume() {
        super.onResume()
        // If overlay permission was just granted and user enabled floating bubble, start it
        val prefs = getSharedPreferences("lifedeck_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("floating_bubble_enabled", false)
        val alpha = prefs.getFloat("floating_bubble_alpha", 0.85f)
        if (enabled && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))) {
            val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
                putExtra("EXTRA_ALPHA", alpha)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("OPEN_QUICK_ADD", false)) {
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('lifedeck:quickadd'))", null)
        }
    }

    inner class LifeDeckBridge(private val context: Context) {

        @JavascriptInterface
        fun startVoiceInput() {
            runOnUiThread {
                try {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak into LifeDeck...")
                    }
                    voiceInputLauncher.launch(intent)
                } catch (e: Exception) {
                    android.util.Log.e("LifeDeckNative", "Voice input launch error", e)
                }
            }
        }

        @JavascriptInterface
        fun pickSystemSound(isAlarm: Boolean) {
            pendingSoundIsAlarm = isAlarm
            runOnUiThread {
                try {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(
                            RingtoneManager.EXTRA_RINGTONE_TYPE,
                            if (isAlarm) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
                        )
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        val prefs = getSharedPreferences("lifedeck_prefs", Context.MODE_PRIVATE)
                        val currentUri = prefs.getString(if (isAlarm) "alarm_sound_uri" else "notification_sound_uri", null)
                        if (!currentUri.isNullOrBlank()) {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
                        }
                    }
                    ringtonePickerLauncher.launch(intent)
                } catch (e: Exception) {
                    android.util.Log.e("LifeDeckNative", "Ringtone picker error", e)
                }
            }
        }

        @JavascriptInterface
        fun toggleFloatingBubble(enabled: Boolean, alpha: Float = 0.85f) {
            val prefs = getSharedPreferences("lifedeck_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("floating_bubble_enabled", enabled)
                .putFloat("floating_bubble_alpha", alpha)
                .apply()

            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${this@MainActivity.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    this@MainActivity.startActivity(intent)
                    return
                }

                val intent = Intent(this@MainActivity, FloatingBubbleService::class.java).apply {
                    putExtra("EXTRA_ALPHA", alpha)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    this@MainActivity.startForegroundService(intent)
                } else {
                    this@MainActivity.startService(intent)
                }
            } else {
                val intent = Intent(this@MainActivity, FloatingBubbleService::class.java)
                this@MainActivity.stopService(intent)
            }
        }

        @JavascriptInterface
        fun setFloatingBubbleAlpha(alpha: Float) {
            val prefs = getSharedPreferences("lifedeck_prefs", Context.MODE_PRIVATE)
            prefs.edit().putFloat("floating_bubble_alpha", alpha).apply()

            val intent = Intent(this@MainActivity, FloatingBubbleService::class.java).apply {
                action = "UPDATE_ALPHA"
                putExtra("EXTRA_ALPHA", alpha)
            }
            this@MainActivity.startService(intent)
        }

        @JavascriptInterface
        fun scheduleAlarm(id: String, title: String, triggerAtMillis: Long, type: String) {
            try {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

                // Check exact alarm permissions on Android 12+ (API 31+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                }

                val intent = Intent(this@MainActivity, ReminderReceiver::class.java).apply {
                    action = ReminderReceiver.ACTION_DIRECT_ALARM
                    putExtra("EXTRA_ID", id)
                    putExtra("EXTRA_TITLE", title)
                    putExtra("EXTRA_TYPE", type)
                }
                val pending = PendingIntent.getBroadcast(
                    this@MainActivity,
                    ("alarm:$id").hashCode() and 0x7FFFFFFF,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                AlarmManagerCompat.setExactAndAllowWhileIdle(
                    alarmManager,
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pending
                )
            } catch (e: Exception) {
                android.util.Log.e("LifeDeckAlarm", "Error scheduling exact alarm", e)
            }
        }

        @JavascriptInterface
        fun cancelAlarm(id: String) {
            try {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(this@MainActivity, ReminderReceiver::class.java).apply {
                    action = ReminderReceiver.ACTION_DIRECT_ALARM
                }
                val pending = PendingIntent.getBroadcast(
                    this@MainActivity,
                    ("alarm:$id").hashCode() and 0x7FFFFFFF,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarmManager.cancel(pending)
            } catch (e: Exception) {
                android.util.Log.e("LifeDeckAlarm", "Error cancelling alarm", e)
            }
        }

        @JavascriptInterface
        fun syncToSheets(webhookUrl: String, payloadJson: String): String {
            val prefs = getSharedPreferences("lifedeck_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("sheets_webhook_url", webhookUrl).apply()

            // Update Android repository settings so background MaintenanceWorker syncs automatically
            try {
                val app = applicationContext as? com.lifedeck.app.LifeDeckApp
                app?.container?.settings?.let { repo ->
                    kotlinx.coroutines.runBlocking {
                        repo.update { it.copy(syncUrl = webhookUrl, syncEnabled = true) }
                    }
                }
            } catch (_: Exception) {}

            var cleanUrl = webhookUrl.trim()
            if (cleanUrl.contains("/edit")) {
                cleanUrl = cleanUrl.replace(Regex("/edit.*$"), "/exec")
            }
            if (cleanUrl.contains("/dev")) {
                cleanUrl = cleanUrl.replace(Regex("/dev.*$"), "/exec")
            }

            if (cleanUrl.contains("docs.google.com/spreadsheets")) {
                return """{"ok":false,"error":"Please paste your Google Apps Script Web App URL (ending in /exec), not the spreadsheet viewer URL."}"""
            }

            var attempt = 0
            var finalResponse = """{"ok":false,"error":"Unable to connect to Google Sheets."}"""

            while (attempt < 3) {
                attempt++
                try {
                    var currentUrl = cleanUrl
                    var currentMethod = "POST"
                    var currentBody: String? = payloadJson
                    var redirects = 0
                    val maxRedirects = 6
                    var success = false

                    while (redirects < maxRedirects) {
                        val conn = (java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection).apply {
                            instanceFollowRedirects = false
                            connectTimeout = 20_000
                            readTimeout = 30_000
                            requestMethod = currentMethod
                            if (currentMethod == "POST" && currentBody != null) {
                                doOutput = true
                                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            }
                        }

                        if (currentMethod == "POST" && currentBody != null) {
                            conn.outputStream.use { it.write(currentBody.toByteArray(Charsets.UTF_8)) }
                        }

                        val code = conn.responseCode
                        if (code in 300..399) {
                            val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
                            if (loc != null) {
                                currentUrl = loc
                                currentMethod = "GET"
                                currentBody = null
                                redirects++
                                conn.disconnect()
                                continue
                            }
                        }

                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                        conn.disconnect()

                        if (code in 200..299) {
                            finalResponse = if (text.isBlank()) """{"ok":true,"count":1}""" else text
                            success = true
                        } else {
                            finalResponse = """{"ok":false,"error":"HTTP $code: ${text.take(160)}"}"""
                        }
                        break
                    }

                    if (success) {
                        return finalResponse
                    }
                } catch (e: Exception) {
                    finalResponse = """{"ok":false,"error":${JSONObject.quote(e.message ?: "Connection error")}}"""
                }

                if (attempt < 3) {
                    try { Thread.sleep(1200L * attempt) } catch (_: Exception) {}
                }
            }

            return finalResponse
        }

        @JavascriptInterface
        fun getPendingOverlayEntries(): String {
            val prefs = getSharedPreferences("lifedeck_prefs", Context.MODE_PRIVATE)
            val entriesJson = prefs.getString("pending_overlay_entries", "[]") ?: "[]"
            prefs.edit().remove("pending_overlay_entries").apply()
            return entriesJson
        }
    }
}
