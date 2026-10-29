package ai.zyraxon.assistant

import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The floating chat bubble the user asked for: a small orb that stays on top of
 * every other app, expands into a chat box, and sends straight into the same
 * assistant the main screen uses.
 *
 * It is deliberately thin. All the thinking happens in the ViewModel the app
 * already has; this only draws a window and pushes text across. When the process
 * has no live ViewModel (the activity was killed), the bubble says so instead of
 * silently swallowing what the user typed.
 *
 * Views are built in code rather than Compose: a Compose overlay would need a
 * saved-state host and a lifecycle owner inside a service, which is a lot of
 * machinery for one box.
 */
class OverlayChatService : android.app.Service() {

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var panel: View? = null
    private var scope: CoroutineScope? = null
    private var replyJob: Job? = null
    private lateinit var statusLine: TextView
    private lateinit var replyLine: TextView
    private lateinit var input: EditText

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDrawOverlays()) {
            // Without this the addView below throws and the service dies. Stop
            // quietly; the settings row tells the user what to grant.
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) showBubble()
        return START_STICKY
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun windowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    /** The collapsed orb. Dragging moves it; tapping opens the box. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val size = dp(56)
        val orb = TextView(this).apply {
            text = "Z"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E5399B"))
                setStroke(dp(2), Color.parseColor("#FFD6EE"))
            }
            elevation = dp(8).toFloat()
        }

        val p = WindowManager.LayoutParams(
            size, size,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(180)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false

        orb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    touchX = event.rawX; touchY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) dragged = true
                    p.x = startX + dx.toInt()
                    p.y = startY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(orb, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // A drag must not also count as a tap, or the box opens every
                    // time the orb is moved.
                    if (!dragged) openPanel()
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(orb, p) }
        bubble = orb
    }

    /** The expanded box: live reply, a status line and one input. */
    private fun openPanel() {
        if (panel != null) return
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null

        val vm = ZyraxonViewModel.active

        statusLine = TextView(this).apply {
            setTextColor(Color.parseColor("#FF9BD8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = if (vm == null) "অ্যাপটি বন্ধ — খুলে আবার চেষ্টা করো" else "প্রস্তুত"
        }
        replyLine = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 6
            text = ""
        }
        input = EditText(this).apply {
            hint = "কী করতে চাও লিখো…"
            setHintTextColor(Color.parseColor("#99FFFFFF"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val send = TextView(this).apply {
            text = "পাঠাও"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#E5399B"))
            }
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    input.setText("")
                    sendToAssistant(text)
                }
            }
        }

        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { closePanel() }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(this@OverlayChatService).apply {
                    text = "ZYRAXON"
                    setTextColor(Color.parseColor("#FFD6EE"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(close)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F00B0A18"))
                setStroke(dp(1), Color.parseColor("#66FF69CA"))
            }
            addView(titleRow)
            addView(replyLine, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
            addView(statusLine)
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }

        val p = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }

        runCatching { windowManager.addView(root, p) }
        panel = root

        if (vm != null) {
            vm.setOverlayExpanded(true)
            observe(vm)
        }
    }

    /** Mirrors the live answer into the box, so the user reads it as it streams. */
    private fun observe(vm: ZyraxonViewModel) {
        replyJob?.cancel()
        replyJob = scope?.launch {
            launch {
                vm.messages.collectLatest { list ->
                    val lastAssistant = list.lastOrNull { it.role == ai.zyraxon.assistant.model.MessageRole.ASSISTANT }
                    replyLine.text = lastAssistant?.text?.take(600).orEmpty()
                }
            }
            launch {
                vm.isGenerating.collectLatest { busy ->
                    statusLine.text = when {
                        busy -> "ভাবছি…"
                        else -> "প্রস্তুত"
                    }
                }
            }
        }
    }

    private fun sendToAssistant(text: String) {
        val vm = ZyraxonViewModel.active
        if (vm == null) {
            statusLine.text = "অ্যাপটি বন্ধ — খুলে আবার চেষ্টা করো"
            return
        }
        statusLine.text = "পাঠানো হয়েছে…"
        // Reuses the real chat path, so tools, memory and voice all behave exactly
        // as they do on the main screen.
        vm.sendMessage(text)
    }

    private fun closePanel() {
        replyJob?.cancel()
        replyJob = null
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        ZyraxonViewModel.active?.setOverlayExpanded(false)
        showBubble()
    }

    override fun onDestroy() {
        replyJob?.cancel()
        scope?.cancel()
        panel?.let { runCatching { windowManager.removeView(it) } }
        bubble?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        bubble = null
        ZyraxonViewModel.active?.setOverlayExpanded(false)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            // A plain startService: the overlay is not a long-running foreground
            // job, and startForegroundService would require a notification within
            // five seconds or the system kills the process.
            val intent = Intent(context, OverlayChatService::class.java)
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayChatService::class.java)) }
        }
    }
}
