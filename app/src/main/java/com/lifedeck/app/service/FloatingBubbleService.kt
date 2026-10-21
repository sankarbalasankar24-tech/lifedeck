package com.lifedeck.app.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.lifedeck.app.MainActivity
import com.lifedeck.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * High-performance, always-on Floating Calendar Bubble Service.
 * Opens ONLY a native Quick Entry overlay window directly on top of the screen.
 * Never flashes or launches the parent app.
 * Persists over "clear all notifications" and "close all apps" (onTaskRemoved auto-restart).
 */
class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var overlayDialogView: View? = null

    private lateinit var prefs: SharedPreferences

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("lifedeck_prefs", Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        setupFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alpha = intent?.getFloatExtra("EXTRA_ALPHA", -1f) ?: -1f
        if (alpha > 0f) {
            params?.alpha = alpha
            if (bubbleView != null && windowManager != null) {
                try {
                    windowManager?.updateViewLayout(bubbleView, params)
                } catch (_: Exception) {}
            }
        }
        return START_STICKY
    }

    /**
     * Auto-restart service if user clears all apps from recents ("close all apps").
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val restartIntent = Intent(applicationContext, FloatingBubbleService::class.java).apply {
                setPackage(packageName)
            }
            val pendingIntent = PendingIntent.getService(
                this, 1994, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 800,
                pendingIntent
            )
        } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val savedAlpha = prefs.getFloat("floating_bubble_alpha", 0.85f)

        params = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(18)
            y = dp(95)
            this.alpha = savedAlpha
        }

        // Calendar icon button with dark nocturnal background and emerald accents
        val imageView = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble_calendar)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#141D1B"))
                setStroke(dp(1.5f), Color.parseColor("#4EDEA3"))
            }
            background = bg
            setPadding(dp(13), dp(13), dp(13), dp(13))
            elevation = dp(14).toFloat()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        imageView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x
                    initialY = params!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (initialTouchY - event.rawY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isClick = false
                    }
                    params!!.x = initialX + dx
                    params!!.y = initialY + dy
                    try {
                        windowManager?.updateViewLayout(bubbleView, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        // Open ONLY the native Quick Entry overlay window directly on screen
                        showFloatingQuickEntry()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = imageView
        try {
            windowManager?.addView(bubbleView, params)
        } catch (_: Exception) {}
    }

    /**
     * Native Quick Entry overlay dialog matching Photo 3.
     * Appears directly over any screen without opening MainActivity.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingQuickEntry() {
        if (overlayDialogView != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.65f
            gravity = Gravity.CENTER
        }

        // Fullscreen root container with click-outside-to-dismiss
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismissOverlay() }
        }

        // Card Container
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#141D1B"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1.5f), Color.parseColor("#273B37"))
            }
            background = bg
            setPadding(dp(20), dp(20), dp(20), dp(20))
            elevation = dp(24).toFloat()
            setOnClickListener { /* prevent dismissal when tapping card */ }
        }

        val cardParams = FrameLayout.LayoutParams(
            dp(340),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // 1. Header: Calendar Icon + "Quick Entry" + Close Button (X)
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
        }

        val headerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble_calendar)
            val lp = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) }
            layoutParams = lp
        }
        headerLayout.addView(headerIcon)

        val headerTitle = TextView(this).apply {
            text = "Quick Entry"
            textSize = 17f
            setTextColor(Color.parseColor("#F2F7F4"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(headerTitle)

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#9BB3AA"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { dismissOverlay() }
        }
        headerLayout.addView(closeBtn)
        cardLayout.addView(headerLayout)

        // 2. Text Input Box
        val inputContainer = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#0E1514"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#273B37"))
            }
            background = bg
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
        }

        val inputField = EditText(this).apply {
            hint = "Type task, grocery, or project..."
            setHintTextColor(Color.parseColor("#6B827A"))
            setTextColor(Color.parseColor("#F2F7F4"))
            textSize = 14f
            background = null
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inputContainer.addView(inputField)
        cardLayout.addView(inputContainer)

        var selectedCategory = "TASK"
        var selectedDue = "TODAY"
        var selectedPriority = "MEDIUM"

        // 3. CATEGORY Row
        val catLabel = TextView(this).apply {
            text = "CATEGORY:"
            textSize = 10f
            setTextColor(Color.parseColor("#6B827A"))
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            layoutParams = lp
        }
        cardLayout.addView(catLabel)

        val catLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            layoutParams = lp
        }

        val catButtons = mutableListOf<Button>()
        val catOptions = listOf("TASK" to "Task", "SHOP" to "Shop", "PROJECT" to "Project")

        catOptions.forEach { (catId, catText) ->
            val btn = Button(this).apply {
                text = catText
                textSize = 11f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            }
            catButtons.add(btn)
            catLayout.addView(btn)
        }

        fun updateCategoryStyles() {
            catButtons.forEachIndexed { idx, btn ->
                val (catId, _) = catOptions[idx]
                val isSel = catId == selectedCategory
                btn.setTextColor(Color.parseColor(if (isSel) "#4EDEA3" else "#9BB3AA"))
                btn.background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isSel) "#183B2E" else "#111917"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor(if (isSel) "#4EDEA3" else "#273B37"))
                }
                btn.setOnClickListener {
                    selectedCategory = catId
                    updateCategoryStyles()
                }
            }
        }
        updateCategoryStyles()
        cardLayout.addView(catLayout)

        // 4. DUE DATE Row
        val dueLabel = TextView(this).apply {
            text = "DUE DATE:"
            textSize = 10f
            setTextColor(Color.parseColor("#6B827A"))
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            layoutParams = lp
        }
        cardLayout.addView(dueLabel)

        val dueLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
        }

        val dueButtons = mutableListOf<Button>()
        val dueOptions = listOf("TODAY" to "Today", "TOMORROW" to "Tomorrow")

        dueOptions.forEach { (dId, dLabel) ->
            val btn = Button(this).apply {
                text = dLabel
                textSize = 11f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            }
            dueButtons.add(btn)
            dueLayout.addView(btn)
        }

        fun updateDueStyles() {
            dueButtons.forEachIndexed { idx, btn ->
                val (dId, _) = dueOptions[idx]
                val isSel = dId == selectedDue
                btn.setTextColor(Color.parseColor(if (isSel) "#4EDEA3" else "#9BB3AA"))
                btn.background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isSel) "#183B2E" else "#111917"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor(if (isSel) "#4EDEA3" else "#273B37"))
                }
                btn.setOnClickListener {
                    selectedDue = dId
                    updateDueStyles()
                }
            }
        }
        updateDueStyles()
        cardLayout.addView(dueLayout)

        // 5. SAVE Button (Full width emerald green)
        val saveBtn = Button(this).apply {
            text = "SAVE ✈"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.parseColor("#003824"))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4EDEA3"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { bottomMargin = dp(10) }

            setOnClickListener {
                val text = inputField.text.toString().trim()
                if (text.isEmpty()) {
                    dismissOverlay()
                    return@setOnClickListener
                }
                saveEntryLocallyAndSync(text, selectedCategory, selectedPriority, selectedDue)
                Toast.makeText(this@FloatingBubbleService, "✓ Saved to LifeDeck", Toast.LENGTH_SHORT).show()
                dismissOverlay()
            }
        }
        cardLayout.addView(saveBtn)

        // 6. Footer Tip
        val tipView = TextView(this).apply {
            text = "Tip: If nothing is selected, entry saves directly to Inbox for end-of-day scheduling."
            textSize = 10f
            setTextColor(Color.parseColor("#6B827A"))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        cardLayout.addView(tipView)

        rootLayout.addView(cardLayout, cardParams)
        overlayDialogView = rootLayout

        try {
            windowManager?.addView(overlayDialogView, overlayParams)
        } catch (_: Exception) {}
    }

    private fun dismissOverlay() {
        overlayDialogView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
            overlayDialogView = null
        }
    }

    private fun saveEntryLocallyAndSync(
        text: String,
        category: String,
        priority: String,
        due: String
    ) {
        val now = System.currentTimeMillis()
        val dueTimestamp = if (due == "TOMORROW") now + 86400000L else now

        val entryObj = JSONObject().apply {
            put("id", "entry-$now")
            put("title", text)
            put("type", if (category == "SHOP") "SHOPPING" else "TASK")
            put("status", "ACTIVE")
            put("priority", priority)
            put("dueAt", dueTimestamp)
            put("scheduledAt", dueTimestamp)
            put("createdAt", now)
            put("updatedAt", now)
        }

        // Store into pending overlay entries in SharedPreferences
        try {
            val existing = prefs.getString("pending_overlay_entries", "[]") ?: "[]"
            val array = JSONArray(existing)
            array.put(entryObj)
            prefs.edit().putString("pending_overlay_entries", array.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("LifeDeckBubble", "Error saving overlay entry", e)
        }

        // Send to Google Sheets immediately in background without needing the main app open
        val webhookUrl = prefs.getString("sheets_webhook_url", null)
        if (!webhookUrl.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    var currentUrl = webhookUrl.trim()
                    if (currentUrl.contains("/edit")) {
                        currentUrl = currentUrl.replace(Regex("/edit.*$"), "/exec")
                    }

                    val rowObj = JSONObject().apply {
                        put("id", "entry-$now")
                        put("op", "CREATE")
                        put("title", text)
                        put("status", "ACTIVE")
                        put("type", if (category == "SHOP") "SHOPPING" else "TASK")
                        put("priority", priority)
                        put("dueAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(dueTimestamp))
                        put("row", entryObj)
                    }

                    val payload = JSONObject().apply {
                        put("action", "push")
                        put("table", "LifeDeck")
                        put("rows", JSONArray().put(rowObj))
                    }

                    val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 12000
                        readTimeout = 12000
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }

                    conn.outputStream.use { os ->
                        os.write(payload.toString().toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    val code = conn.responseCode
                    conn.disconnect()
                    android.util.Log.d("LifeDeckBubble", "Sheets quick sync response: $code")
                } catch (err: Exception) {
                    android.util.Log.w("LifeDeckBubble", "Background sheets sync failed", err)
                }
            }
        }
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()
    }

    private fun dp(value: Int): Int = dp(value.toFloat())

    override fun onDestroy() {
        super.onDestroy()
        dismissOverlay()
        bubbleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LifeDeck Quick Entry Overlay",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps LifeDeck Quick Entry active over other apps"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LifeDeck Quick Entry Active")
            .setContentText("Always-on floating button is ready")
            .setSmallIcon(R.drawable.ic_stat_lifedeck)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "lifedeck_overlay_channel"
        private const val NOTIFICATION_ID = 8921
    }
}
