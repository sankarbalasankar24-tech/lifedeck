package com.lifedeck.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lifedeck.app.MainActivity
import com.lifedeck.app.R
import com.lifedeck.app.notify.ReminderReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * High-performance, persistent Foreground Service hosting the LifeDeck Quick Entry overlay
 * and always-on floating botanical bubble.
 */
class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var overlayDialogView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private lateinit var prefs: SharedPreferences

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("lifedeck_prefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        setupFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_QUICK_ENTRY) {
            showFloatingQuickEntry()
        }

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val restartServiceIntent = Intent(applicationContext, FloatingBubbleService::class.java).apply {
                setPackage(packageName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartServiceIntent)
            } else {
                startService(restartServiceIntent)
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        if (bubbleView != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val storedAlpha = prefs.getFloat("bubble_alpha", 0.85f)
        val initialX = prefs.getInt("bubble_x", 16)
        val initialY = prefs.getInt("bubble_y", 220)

        params = WindowManager.LayoutParams(
            dp(52),
            dp(52),
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
            alpha = storedAlpha
        }

        val imageView = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble_calendar)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#141D1B"))
                setStroke(dp(1.5f), Color.parseColor("#4EDEA3"))
            }
            elevation = dp(10).toFloat()
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        imageView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    bubbleInitialX = params?.x ?: 0
                    bubbleInitialY = params?.y ?: 0
                    isDragging = false
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params?.x = bubbleInitialX + dx
                        params?.y = bubbleInitialY + dy
                        try {
                            windowManager?.updateViewLayout(bubbleView, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    val isClick = !isDragging && Math.abs(dx) < dp(8) && Math.abs(dy) < dp(8)

                    params?.let {
                        prefs.edit()
                            .putInt("bubble_x", it.x)
                            .putInt("bubble_y", it.y)
                            .apply()
                    }

                    if (isClick) {
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

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var bubbleInitialX = 0
    private var bubbleInitialY = 0
    private var isDragging = false

    /**
     * Side edge to side edge, centered on screen, semi-transparent background Quick Entry overlay.
     * Pure text capsules matching app font, working microphone with permission prompt,
     * themed time selector for alarm, and auto-dismiss on outside touch or back button.
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
            dimAmount = 0.45f
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        // Root container: intercepts touches outside the card to dismiss, and catches back button
        val rootLayout = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissOverlay()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.argb(105, 0, 0, 0)) // Semi-transparent backdrop scrim
            setOnClickListener { dismissOverlay() }
        }

        // Scrollable wrapper so keyboard won't compress fields
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnClickListener { dismissOverlay() }
        }

        val scrollCenterWrapper = FrameLayout(this).apply {
            setOnClickListener { dismissOverlay() }
        }

        // Card layout: side edge to side edge with 14dp margins, semi-transparent botanical nocturne background
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(228, 17, 27, 24)) // ~89% opacity deep botanical nocturne
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1.5f), Color.argb(180, 78, 222, 163)) // Subtle glowing emerald outline
            }
            setPadding(dp(18), dp(18), dp(18), dp(18))
            elevation = dp(24).toFloat()
            setOnClickListener { /* Prevent dismissal when clicking inside the card */ }
        }

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(14)
            rightMargin = dp(14)
            topMargin = dp(24)
            bottomMargin = dp(24)
        }

        // 1. Header: ic_bubble_calendar + "Quick Entry" + Close (X)
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
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
            textSize = 16f
            setTextColor(Color.parseColor("#F2F7F4"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
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

        // 2. Input Box with Emerald Border and Mic Icon inside
        val inputContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 14, 21, 20))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1.5f), Color.parseColor("#4EDEA3"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
        }

        val inputField = EditText(this).apply {
            hint = "Type anything or tap mic..."
            setHintTextColor(Color.parseColor("#6B827A"))
            setTextColor(Color.parseColor("#F2F7F4"))
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            background = null
            setPadding(dp(14), dp(12), dp(44), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        currentInputField = inputField
        inputContainer.addView(inputField)

        val micButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22312D"))
                setStroke(dp(1), Color.parseColor("#273B37"))
            }
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = dp(8)
            }
            setOnClickListener {
                startVoiceInputWorkflow()
            }
        }
        inputContainer.addView(micButton)
        cardLayout.addView(inputContainer)

        var selectedCategory = "TASK"
        var selectedDue = "TODAY"
        var selectedPriority = "NORMAL"
        var customDateString: String? = null
        var alarmOn = false

        // Alarm time state: defaults to 9:00 AM
        val cal = Calendar.getInstance()
        var alarmHour = 9
        var alarmMinute = 0

        // 3. CATEGORY Row - Pure capsules with names, font matched
        val catLabel = TextView(this).apply {
            text = "CATEGORY:"
            textSize = 10f
            setTextColor(Color.parseColor("#6B827A"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.05f
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

        val catOptions = listOf(
            "TASK" to "Task",
            "SHOP" to "Shop",
            "PROJECT" to "Project"
        )
        val catButtons = mutableListOf<Button>()

        catOptions.forEach { (catId, catText) ->
            val btn = Button(this).apply {
                text = catText
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
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
                    setColor(if (isSel) Color.argb(85, 78, 222, 163) else Color.argb(120, 20, 29, 27))
                    cornerRadius = dp(12).toFloat()
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

        // 4. DUE DATE Row - Pure capsules with names, font matched
        val dueLabel = TextView(this).apply {
            text = "DUE DATE:"
            textSize = 10f
            setTextColor(Color.parseColor("#6B827A"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.05f
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
            ).apply { bottomMargin = dp(12) }
            layoutParams = lp
        }

        val dueButtons = mutableListOf<Button>()
        val dueOptions = listOf(
            "TODAY" to "Today",
            "TOMORROW" to "Tomorrow",
            "PICK_DATE" to "Pick Date"
        )

        dueOptions.forEach { (dId, dLabel) ->
            val btn = Button(this).apply {
                text = dLabel
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            }
            dueButtons.add(btn)
            dueLayout.addView(btn)
        }

        fun updateDueStyles() {
            dueButtons.forEachIndexed { idx, btn ->
                val (dId, dLabel) = dueOptions[idx]
                val isSel = dId == selectedDue
                val displayLabel = if (dId == "PICK_DATE" && customDateString != null) {
                    customDateString ?: dLabel
                } else {
                    dLabel
                }
                btn.text = displayLabel
                btn.setTextColor(Color.parseColor(if (isSel) "#4EDEA3" else "#9BB3AA"))
                btn.background = GradientDrawable().apply {
                    setColor(if (isSel) Color.argb(85, 78, 222, 163) else Color.argb(120, 20, 29, 27))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor(if (isSel) "#4EDEA3" else "#273B37"))
                }
                btn.setOnClickListener {
                    if (dId == "PICK_DATE") {
                        val c = Calendar.getInstance()
                        val dpd = DatePickerDialog(
                            this@FloatingBubbleService,
                            R.style.Theme_LifeDeck_Dialog,
                            { _, y, m, d ->
                                customDateString = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                                selectedDue = "PICK_DATE"
                                updateDueStyles()
                            },
                            c.get(Calendar.YEAR),
                            c.get(Calendar.MONTH),
                            c.get(Calendar.DAY_OF_MONTH)
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            dpd.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                        } else {
                            @Suppress("DEPRECATION")
                            dpd.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
                        }
                        dpd.show()
                    } else {
                        selectedDue = dId
                        updateDueStyles()
                    }
                }
            }
        }
        updateDueStyles()
        cardLayout.addView(dueLayout)

        // 5. PRIORITY Row - Pure capsules with names, font matched
        val prioLabel = TextView(this).apply {
            text = "PRIORITY:"
            textSize = 10f
            setTextColor(Color.parseColor("#6B827A"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.05f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            layoutParams = lp
        }
        cardLayout.addView(prioLabel)

        val prioLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
        }

        val prioButtons = mutableListOf<Button>()
        val prioOptions = listOf("LOW" to "Low", "NORMAL" to "Normal", "URGENT" to "Urgent")

        prioOptions.forEach { (pId, pLabel) ->
            val btn = Button(this).apply {
                text = pLabel
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            }
            prioButtons.add(btn)
            prioLayout.addView(btn)
        }

        fun updatePriorityStyles() {
            prioButtons.forEachIndexed { idx, btn ->
                val (pId, _) = prioOptions[idx]
                val isSel = pId == selectedPriority
                val activeColor = if (pId == "URGENT") "#F87171" else if (pId == "NORMAL") "#4EDEA3" else "#A7F3D0"
                btn.setTextColor(Color.parseColor(if (isSel) activeColor else "#9BB3AA"))
                btn.background = GradientDrawable().apply {
                    setColor(if (isSel) Color.argb(85, 78, 222, 163) else Color.argb(120, 20, 29, 27))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor(if (isSel) activeColor else "#273B37"))
                }
                btn.setOnClickListener {
                    selectedPriority = pId
                    updatePriorityStyles()
                }
            }
        }
        updatePriorityStyles()
        cardLayout.addView(prioLayout)

        // 6. SET ALARM Row with Time Selector
        val alarmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            layoutParams = lp
        }

        val alarmText = TextView(this).apply {
            text = "SET ALARM"
            textSize = 11f
            setTextColor(Color.parseColor("#9BB3AA"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        alarmRow.addView(alarmText)

        val alarmToggle = TextView(this).apply {
            text = "Off"
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#9BB3AA"))
            background = GradientDrawable().apply {
                setColor(Color.argb(120, 27, 39, 36))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#273B37"))
            }
            setPadding(dp(12), dp(4), dp(12), dp(4))
            isClickable = true
        }
        alarmRow.addView(alarmToggle)
        cardLayout.addView(alarmRow)

        // Themed Time Selector Container (Shown when alarm is enabled)
        val timeSelectorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(180, 14, 21, 20))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#273B37"))
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
            visibility = View.GONE
        }

        val timeHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            layoutParams = lp
        }

        fun formatTimeDisplay(h: Int, m: Int): String {
            val amPm = if (h >= 12) "PM" else "AM"
            val displayHour = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            return String.format(Locale.US, "%02d:%02d %s", displayHour, m, amPm)
        }

        val timeDisplay = TextView(this).apply {
            text = "Time: " + formatTimeDisplay(alarmHour, alarmMinute)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#4EDEA3"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeHeaderRow.addView(timeDisplay)

        val changeTimeBtn = TextView(this).apply {
            text = "Pick Time ⏰"
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#9BB3AA"))
            background = GradientDrawable().apply {
                setColor(Color.argb(150, 27, 39, 36))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#38534C"))
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                val tpd = TimePickerDialog(
                    this@FloatingBubbleService,
                    R.style.Theme_LifeDeck_Dialog,
                    { _, h, m ->
                        alarmHour = h
                        alarmMinute = m
                        timeDisplay.text = "Time: " + formatTimeDisplay(alarmHour, alarmMinute)
                    },
                    alarmHour,
                    alarmMinute,
                    false
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tpd.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                } else {
                    @Suppress("DEPRECATION")
                    tpd.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
                }
                tpd.show()
            }
        }
        timeHeaderRow.addView(changeTimeBtn)
        timeSelectorContainer.addView(timeHeaderRow)

        // Quick time chips: 9 AM, 12:30 PM, 6 PM
        val quickChipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }

        val quickTimes = listOf(
            Triple("9:00 AM", 9, 0),
            Triple("12:30 PM", 12, 30),
            Triple("6:00 PM", 18, 0)
        )

        quickTimes.forEach { (label, h, m) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 10.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor("#9BB3AA"))
                background = GradientDrawable().apply {
                    setColor(Color.argb(100, 27, 39, 36))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.parseColor("#273B37"))
                }
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                gravity = Gravity.CENTER
                setOnClickListener {
                    alarmHour = h
                    alarmMinute = m
                    timeDisplay.text = "Time: " + formatTimeDisplay(alarmHour, alarmMinute)
                }
            }
            quickChipsRow.addView(chip)
        }
        timeSelectorContainer.addView(quickChipsRow)
        cardLayout.addView(timeSelectorContainer)

        alarmToggle.setOnClickListener {
            alarmOn = !alarmOn
            if (alarmOn) {
                alarmToggle.text = "Alarm ON"
                alarmToggle.setTextColor(Color.parseColor("#003824"))
                alarmToggle.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#4EDEA3"))
                    cornerRadius = dp(14).toFloat()
                }
                timeSelectorContainer.visibility = View.VISIBLE
            } else {
                alarmToggle.text = "Off"
                alarmToggle.setTextColor(Color.parseColor("#9BB3AA"))
                alarmToggle.background = GradientDrawable().apply {
                    setColor(Color.argb(120, 27, 39, 36))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.parseColor("#273B37"))
                }
                timeSelectorContainer.visibility = View.GONE
            }
        }

        // 7. Full-width SAVE button
        val saveBtn = Button(this).apply {
            text = "SAVE  ✈"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#003824"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4EDEA3"))
                cornerRadius = dp(16).toFloat()
            }
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                topMargin = dp(6)
            }
            layoutParams = lp
            setOnClickListener {
                val textVal = inputField.text.toString().trim()
                if (textVal.isEmpty()) {
                    Toast.makeText(this@FloatingBubbleService, "Please type or speak an entry", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                saveEntryLocallyAndSync(
                    text = textVal,
                    category = selectedCategory,
                    priority = selectedPriority,
                    due = selectedDue,
                    customDate = customDateString,
                    alarmOn = alarmOn,
                    alarmHour = alarmHour,
                    alarmMinute = alarmMinute
                )
                dismissOverlay()
                Toast.makeText(this@FloatingBubbleService, "Saved to $selectedCategory", Toast.LENGTH_SHORT).show()
            }
        }
        cardLayout.addView(saveBtn)

        scrollCenterWrapper.addView(cardLayout, cardParams)
        scrollView.addView(scrollCenterWrapper)
        rootLayout.addView(scrollView)

        overlayDialogView = rootLayout

        try {
            windowManager?.addView(overlayDialogView, overlayParams)
            rootLayout.requestFocus()
        } catch (_: Exception) {}
    }

    private fun startVoiceInputWorkflow() {
        val hasPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val intent = Intent(this, VoicePermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun dismissOverlay() {
        currentInputField = null
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
        due: String,
        customDate: String?,
        alarmOn: Boolean,
        alarmHour: Int,
        alarmMinute: Int
    ) {
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance()

        val dueTimestamp = when (due) {
            "TOMORROW" -> {
                c.add(Calendar.DAY_OF_YEAR, 1)
                c.set(Calendar.HOUR_OF_DAY, if (alarmOn) alarmHour else 12)
                c.set(Calendar.MINUTE, if (alarmOn) alarmMinute else 0)
                c.set(Calendar.SECOND, 0)
                c.timeInMillis
            }
            "PICK_DATE" -> {
                if (customDate != null) {
                    val parts = customDate.split("-").map { it.toIntOrNull() ?: 0 }
                    if (parts.size == 3) {
                        Calendar.getInstance().apply {
                            set(parts[0], parts[1] - 1, parts[2], if (alarmOn) alarmHour else 12, if (alarmOn) alarmMinute else 0, 0)
                        }.timeInMillis
                    } else now
                } else now
            }
            else -> {
                c.set(Calendar.HOUR_OF_DAY, if (alarmOn) alarmHour else 12)
                c.set(Calendar.MINUTE, if (alarmOn) alarmMinute else 0)
                c.set(Calendar.SECOND, 0)
                c.timeInMillis
            }
        }

        val entryType = when (category) {
            "SHOP" -> "SHOPPING"
            "PROJECT" -> "PROJECT"
            else -> "TASK"
        }

        val entryId = if (category == "PROJECT") "proj-$now" else "entry-$now"

        val entryObj = JSONObject().apply {
            put("id", entryId)
            put("title", text)
            put("type", entryType)
            put("category", category)
            put("status", if (entryType == "TASK" && due == "NONE") "INBOX" else "ACTIVE")
            put("priority", priority)
            put("dueAt", dueTimestamp)
            put("scheduledAt", dueTimestamp)
            put("createdAt", now)
            put("updatedAt", now)
            if (category == "SHOP") {
                put("bucket", "NOW")
            }
        }

        val entryJsonStr = entryObj.toString()

        // 1. Save to SharedPreferences for offline and background retention
        try {
            val existing = prefs.getString("pending_overlay_entries", "[]") ?: "[]"
            val array = JSONArray(existing)
            array.put(entryObj)
            prefs.edit().putString("pending_overlay_entries", array.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("LifeDeckBubble", "Error saving overlay entry", e)
        }

        // 2. Instant Live Sync into active MainActivity WebView if running
        MainActivity.pushOverlayEntry(entryJsonStr)

        // 3. Schedule Alarm if requested
        if (alarmOn) {
            scheduleDirectAlarm(entryId, text, dueTimestamp, entryType)
        }

        // 4. Send to Google Sheets immediately in background
        val webhookUrl = prefs.getString("sheets_webhook_url", null)
        if (!webhookUrl.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    var currentUrl = webhookUrl.trim()
                    if (currentUrl.contains("/edit")) {
                        currentUrl = currentUrl.replace(Regex("/edit.*$"), "/exec")
                    }

                    val rowObj = JSONObject().apply {
                        put("id", entryId)
                        put("op", "CREATE")
                        put("title", text)
                        put("status", "ACTIVE")
                        put("type", entryType)
                        put("priority", priority)
                        put("dueAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(dueTimestamp))
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
                        connectTimeout = 15000
                        readTimeout = 15000
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

    private fun scheduleDirectAlarm(id: String, title: String, triggerAtMillis: Long, type: String) {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // Cannot schedule exact alarm without permission
                return
            }

            val intent = Intent(this, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_DIRECT_ALARM
                putExtra("EXTRA_ID", id)
                putExtra("EXTRA_TITLE", title)
                putExtra("EXTRA_TYPE", type)
            }

            val pending = PendingIntent.getBroadcast(
                this,
                ("alarm:$id").hashCode() and 0x7FFFFFFF,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            AlarmManagerCompat.setExactAndAllowWhileIdle(
                alarmManager,
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pending
            )
        } catch (e: Exception) {
            android.util.Log.e("LifeDeckBubble", "Failed scheduling alarm", e)
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
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Keeps LifeDeck Quick Entry active over other apps"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
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
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_SHOW_QUICK_ENTRY = "com.lifedeck.app.SHOW_QUICK_ENTRY"
        private const val CHANNEL_ID = "lifedeck_overlay_channel"
        private const val NOTIFICATION_ID = 8921

        var currentInputField: EditText? = null

        fun setSpokenText(spoken: String) {
            currentInputField?.post {
                val existing = currentInputField?.text?.toString()?.trim() ?: ""
                currentInputField?.setText(if (existing.isEmpty()) spoken else "$existing $spoken")
                currentInputField?.setSelection(currentInputField?.text?.length ?: 0)
            }
        }
    }
}
