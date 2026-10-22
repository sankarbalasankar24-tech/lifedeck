package com.lifedeck.app.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import java.util.Calendar

/**
 * Robust Foreground Floating Quick Entry Bubble Service.
 * Matches the exact design from image_2a174de6.png with working speech-to-text mic access.
 * Saves immediately to app state and Google Sheets in the background without launching MainActivity.
 * Runs with FOREGROUND_SERVICE_TYPE_SPECIAL_USE and auto-restarts on boot and onTaskRemoved.
 */
class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var overlayDialogView: View? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private lateinit var prefs: SharedPreferences

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("lifedeck_prefs", Context.MODE_PRIVATE)
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

    /**
     * Stricter persistence: auto-restart service when apps are cleared from recents.
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
                SystemClock.elapsedRealtime() + 600,
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

        val imageView = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble_calendar)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#141D1B"))
                setStroke(dp(1.5f), Color.parseColor("#4EDEA3"))
            }
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
     * Exact Quick Entry Overlay Window matching image_2a174de6.png.
     * Features full speech-to-text voice recognition, capsule selectors, and instant sync.
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

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismissOverlay() }
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141D1B"))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1.5f), Color.parseColor("#273B37"))
            }
            setPadding(dp(22), dp(22), dp(22), dp(22))
            elevation = dp(32).toFloat()
            setOnClickListener { /* prevent dismissal */ }
        }

        val cardParams = FrameLayout.LayoutParams(
            dp(350),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // 1. Header: ic_bubble_calendar + "Quick Entry" + Close (X)
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
            val lp = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(8) }
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
            textSize = 17f
            setTextColor(Color.parseColor("#9BB3AA"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { dismissOverlay() }
        }
        headerLayout.addView(closeBtn)
        cardLayout.addView(headerLayout)

        // 2. Input Box with Emerald Border and Mic Icon inside
        val inputContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0E1514"))
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
            background = null
            setPadding(dp(14), dp(12), dp(44), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
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
                toggleVoiceDictation(inputField, this)
            }
        }
        inputContainer.addView(micButton)
        cardLayout.addView(inputContainer)

        var selectedCategory = "TASK"
        var selectedDue = "TODAY"
        var selectedPriority = "MEDIUM"
        var customDateString: String? = null
        var alarmOn = false

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

        val catOptions = listOf(
            Triple("TASK", "Task", R.drawable.ic_list),
            Triple("SHOP", "Shop", R.drawable.ic_bag),
            Triple("PROJECT", "Project", R.drawable.ic_folder)
        )
        val catButtons = mutableListOf<Button>()

        catOptions.forEach { (catId, catText, iconRes) ->
            val btn = Button(this).apply {
                text = "  $catText"
                textSize = 11f
                isAllCaps = false
                setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            }
            catButtons.add(btn)
            catLayout.addView(btn)
        }

        fun updateCategoryStyles() {
            catButtons.forEachIndexed { idx, btn ->
                val (catId, _, _) = catOptions[idx]
                val isSel = catId == selectedCategory
                btn.setTextColor(Color.parseColor(if (isSel) "#4EDEA3" else "#9BB3AA"))
                btn.background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isSel) "#183B2E" else "#141D1B"))
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
                text = "  $dLabel"
                textSize = 11f
                isAllCaps = false
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_calendar_small, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
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
                btn.text = if (dId == "PICK_DATE" && customDateString != null) "  $customDateString" else "  $dLabel"
                btn.setTextColor(Color.parseColor(if (isSel) "#4EDEA3" else "#9BB3AA"))
                btn.background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isSel) "#183B2E" else "#141D1B"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor(if (isSel) "#4EDEA3" else "#273B37"))
                }
                btn.setOnClickListener {
                    if (dId == "PICK_DATE") {
                        val c = Calendar.getInstance()
                        val dpd = DatePickerDialog(
                            this@FloatingBubbleService,
                            { _, year, monthOfYear, dayOfMonth ->
                                customDateString = String.format("%04d-%02d-%02d", year, monthOfYear + 1, dayOfMonth)
                                selectedDue = "PICK_DATE"
                                updateDueStyles()
                            },
                            c.get(Calendar.YEAR),
                            c.get(Calendar.MONTH),
                            c.get(Calendar.DAY_OF_MONTH)
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            dpd.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
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

        // 5. PRIORITY Row
        val prioLabel = TextView(this).apply {
            text = "PRIORITY:"
            textSize = 10f
            setTextColor(Color.parseColor("#6B827A"))
            typeface = Typeface.DEFAULT_BOLD
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
        val prioOptions = listOf("HIGH" to "• High", "MEDIUM" to "• Medium", "LOW" to "• Low")

        prioOptions.forEach { (pId, pLabel) ->
            val btn = Button(this).apply {
                text = pLabel
                textSize = 11f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
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
                btn.setTextColor(Color.parseColor(if (isSel) "#4EDEA3" else "#9BB3AA"))
                btn.background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isSel) "#183B2E" else "#141D1B"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor(if (isSel) "#4EDEA3" else "#273B37"))
                }
                btn.setOnClickListener {
                    selectedPriority = pId
                    updatePriorityStyles()
                }
            }
        }
        updatePriorityStyles()
        cardLayout.addView(prioLayout)

        // 6. SET ALARM Row
        val alarmLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
        }

        val alarmIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_bell)
            val lp = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(6) }
            layoutParams = lp
        }
        alarmLayout.addView(alarmIcon)

        val alarmText = TextView(this).apply {
            text = "SET ALARM:"
            textSize = 10f
            setTextColor(Color.parseColor("#6B827A"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        alarmLayout.addView(alarmText)

        val alarmToggle = TextView(this).apply {
            text = "Off"
            textSize = 11f
            setTextColor(Color.parseColor("#9BB3AA"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141D1B"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#273B37"))
            }
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener {
                alarmOn = !alarmOn
                text = if (alarmOn) "On" else "Off"
                setTextColor(Color.parseColor(if (alarmOn) "#4EDEA3" else "#9BB3AA"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (alarmOn) "#183B2E" else "#141D1B"))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.parseColor(if (alarmOn) "#4EDEA3" else "#273B37"))
                }
            }
        }
        alarmLayout.addView(alarmToggle)
        cardLayout.addView(alarmLayout)

        // 7. SAVE Button (Full width rounded emerald button)
        val saveBtn = Button(this).apply {
            text = "SAVE ✈"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.parseColor("#003824"))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4EDEA3"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { bottomMargin = dp(10) }

            setOnClickListener {
                val text = inputField.text.toString().trim()
                if (text.isEmpty()) {
                    dismissOverlay()
                    return@setOnClickListener
                }
                saveEntryLocallyAndSync(text, selectedCategory, selectedPriority, selectedDue, customDateString)
                Toast.makeText(this@FloatingBubbleService, "✓ Saved to LifeDeck", Toast.LENGTH_SHORT).show()
                dismissOverlay()
            }
        }
        cardLayout.addView(saveBtn)

        // 8. Footer Tip
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

    private fun toggleVoiceDictation(editText: EditText, micBtn: ImageView) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            return
        }

        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            micBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22312D"))
                setStroke(dp(1), Color.parseColor("#273B37"))
            }
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    micBtn.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#183B2E"))
                        setStroke(dp(1.5f), Color.parseColor("#4EDEA3"))
                    }
                    Toast.makeText(this@FloatingBubbleService, "Listening...", Toast.LENGTH_SHORT).show()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    micBtn.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#22312D"))
                        setStroke(dp(1), Color.parseColor("#273B37"))
                    }
                }
                override fun onError(error: Int) {
                    isListening = false
                    micBtn.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#22312D"))
                        setStroke(dp(1), Color.parseColor("#273B37"))
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spoken = matches[0]
                        val current = editText.text.toString().trim()
                        editText.setText(if (current.isEmpty()) spoken else "$current $spoken")
                        editText.setSelection(editText.text.length)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        editText.setText(matches[0])
                        editText.setSelection(editText.text.length)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun dismissOverlay() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
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
        customDate: String?
    ) {
        val now = System.currentTimeMillis()
        val dueTimestamp = when (due) {
            "TOMORROW" -> now + 86400000L
            "PICK_DATE" -> {
                if (customDate != null) {
                    val parts = customDate.split("-").map { it.toIntOrNull() ?: 0 }
                    if (parts.size == 3) {
                        Calendar.getInstance().apply {
                            set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)
                        }.timeInMillis
                    } else now
                } else now
            }
            else -> now
        }

        val entryObj = JSONObject().apply {
            put("id", "entry-$now")
            put("title", text)
            put("type", if (category == "SHOP") "SHOPPING" else "TASK")
            put("status", if (category == "TASK" && due == "NONE") "INBOX" else "ACTIVE")
            put("priority", priority)
            put("dueAt", dueTimestamp)
            put("scheduledAt", dueTimestamp)
            put("createdAt", now)
            put("updatedAt", now)
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
        try {
            val escaped = entryJsonStr.replace("\\", "\\\\").replace("'", "\\'")
            MainActivity.instance?.runOnUiThread {
                MainActivity.instance?.webView?.evaluateJavascript(
                    "window.onOverlayEntrySaved && window.onOverlayEntrySaved('$escaped')",
                    null
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("LifeDeckBubble", "Could not pass to live WebView", e)
        }

        // 3. Send to Google Sheets immediately in background
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
    }
}
