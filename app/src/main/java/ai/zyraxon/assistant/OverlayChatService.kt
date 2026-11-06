package ai.zyraxon.assistant

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.Vibrator
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The eye that stays on screen.
 *
 * A small watching eye floats on top of every app, at the top right by default.
 * It is a foreground service, so it stays alive when the app is swiped away, and
 * it talks to the same assistant the main screen uses (that assistant now lives
 * for the whole process, not for one screen).
 *
 *  - Tap: the microphone turns on or off. The app does not open. While the
 *    assistant is speaking, a tap cuts it off instead.
 *  - Drag: moves the eye anywhere; it settles against the nearest edge and the
 *    spot is remembered.
 *  - Long press: opens a small chat box.
 *
 * The eye is always there. Turning the microphone off closes the eyelid; it never
 * removes the eye. It is drawn by hand and animated from the display's frame
 * callback, so it keeps moving whatever the phone's animation settings are.
 */
class OverlayChatService : android.app.Service() {

    private lateinit var windowManager: WindowManager
    private var eye: EyeView? = null
    private var eyeParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var scope: CoroutineScope? = null
    private var replyJob: Job? = null
    private var stateJob: Job? = null
    private var heartbeatJob: Job? = null
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
        promoteToForeground()
        if (!canDrawOverlays()) {
            // Without the permission addView would throw. Stay quiet; settings
            // tells the user what to grant.
            stopSelf()
            return START_NOT_STICKY
        }
        if (eye == null) showEye()
        bindState()
        startHeartbeat()
        return START_STICKY
    }

    /** Swiping the app away must not take the eye with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        runCatching {
            val again = Intent(applicationContext, OverlayChatService::class.java)
            val pi = PendingIntent.getService(
                applicationContext, 7, again,
                PendingIntent.FLAG_ONE_SHOT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.set(android.app.AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 800, pi)
        }
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

    // ------------------------------------------------------------ foreground

    /**
     * A foreground service is what keeps this process, the eye and the microphone
     * alive after the app is closed. The microphone type is tried first; where the
     * system refuses it (it only allows that from a visible app on newer Android),
     * the plain type keeps the eye alive.
     */
    private fun promoteToForeground() {
        val chan = "zyraxon_eye"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(chan, t("ZYRAXON চোখ"), NotificationManager.IMPORTANCE_MIN)
            )
        }
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, 0, open,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val note: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chan)
        else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle(t("ZYRAXON জেগে আছে"))
            .setContentText(t("চোখে ট্যাপ করলেই মাইক চালু"))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        val id = 9101
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (Build.VERSION.SDK_INT >= 30 &&
                    checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                try {
                    startForeground(id, note, type)
                } catch (_: Exception) {
                    startForeground(id, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
            } else {
                startForeground(id, note)
            }
        } catch (_: Exception) {
            // Staying a plain service is better than crashing the whole app.
        }
    }

    /**
     * Keeps the bound conversation awake while the app is away.
     *
     * The eye outlives the activity, so it is the one thing that can notice a
     * sandbox has gone to sleep while the phone was in a pocket. It checks every
     * [HEARTBEAT_MS] and wakes the conversation in the background, so the next
     * message — from the eye or from the app after a swipe — lands on a machine
     * that is already up instead of paying the 13–26s wake itself. It is silent:
     * nothing is said unless a wake is actually needed, and a failure is left for
     * the turn to report.
     */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope?.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                val vm = assistant() ?: continue
                runCatching { vm.keepCloudAwake() }
            }
        }
    }

    // ------------------------------------------------------------------- eye

    private fun assistant(): ZyraxonViewModel? =
        ZyraxonViewModel.active ?: runCatching { ZyraxonViewModel.shared(application) }.getOrNull()

    private fun prefs() = getSharedPreferences("zyraxon_eye", Context.MODE_PRIVATE)

    @SuppressLint("ClickableViewAccessibility")
    private fun showEye() {
        val size = dp(64)
        val view = EyeView(this)
        val dm = resources.displayMetrics
        val defaultX = dm.widthPixels - size - dp(6)
        val defaultY = dp(96)
        val p = WindowManager.LayoutParams(
            size, size,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs().getInt("x", defaultX).coerceIn(0, max(0, dm.widthPixels - size))
            y = prefs().getInt("y", defaultY).coerceIn(0, max(0, dm.heightPixels - size))
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        var downAt = 0L
        var longFired = false
        val longPress = Runnable {
            if (!dragged) {
                longFired = true
                buzz(25)
                // A long press toggles the box. It used to only open it, so once
                // the box was up a long press did nothing and the only way out was
                // its own close button — which is not what a press on the eye means.
                if (panel != null) closePanel() else openPanel()
            }
        }

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    touchX = event.rawX; touchY = event.rawY
                    dragged = false; longFired = false
                    downAt = SystemClock.uptimeMillis()
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragged && (abs(dx) > slop * 2 || abs(dy) > slop * 2)) {
                        dragged = true
                        v.removeCallbacks(longPress)
                    }
                    if (dragged) {
                        p.x = (startX + dx.toInt()).coerceIn(0, max(0, dm.widthPixels - size))
                        p.y = (startY + dy.toInt()).coerceIn(0, max(0, dm.heightPixels - size))
                        runCatching { windowManager.updateViewLayout(view, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    when {
                        dragged -> {
                            // Settle against the nearest side edge and remember it.
                            val mid = p.x + size / 2
                            p.x = if (mid < dm.widthPixels / 2) dp(2) else dm.widthPixels - size - dp(2)
                            runCatching { windowManager.updateViewLayout(view, p) }
                            prefs().edit().putInt("x", p.x).putInt("y", p.y).apply()
                        }
                        !longFired && event.action == MotionEvent.ACTION_UP -> onEyeTapped()
                    }
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(view, p) }
        eye = view
        eyeParams = p
    }

    /** A tap is the microphone switch; the app never opens for it. */
    private fun onEyeTapped() {
        buzz(15)
        val vm = assistant()
        if (vm == null) {
            eye?.setNote(t("এখনো প্রস্তুত নয়"))
            return
        }
        vm.toggleListening()
    }

    private fun buzz(ms: Long) {
        runCatching {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(ms)
        }
    }

    /** Feeds the eye the assistant's live state so it looks alive and honest. */
    private fun bindState() {
        if (stateJob?.isActive == true) return
        val vm = assistant() ?: return
        stateJob = scope?.launch {
            launch { vm.alwaysListening.collectLatest { eye?.micOn = it } }
            launch { vm.isListening.collectLatest { eye?.hearing = it } }
            launch { vm.isSpeaking.collectLatest { eye?.speaking = it } }
            launch { vm.isGenerating.collectLatest { eye?.thinking = it } }
            launch { vm.cloudStage.collectLatest { eye?.stage = it } }
            launch { vm.audioLevel.collectLatest { eye?.level = it } }
        }
    }

    // ----------------------------------------------------------------- panel

    /** The expanded box: live reply, a status line and one input. */
    private fun openPanel() {
        if (panel != null) return
        val vm = assistant()

        statusLine = TextView(this).apply {
            setTextColor(Color.parseColor("#FF9BA0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = if (vm == null) t("প্রস্তুত নয়") else t("প্রস্তুত")
        }
        replyLine = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 6
            text = ""
        }
        input = EditText(this).apply {
            hint = t("কী করতে চাও লিখো…")
            setHintTextColor(Color.parseColor("#99FFFFFF"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val send = TextView(this).apply {
            text = t("পাঠাও")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#B3121E"))
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
            text = ""
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
                    setTextColor(Color.parseColor("#FFB4B8"))
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
                setColor(Color.parseColor("#F2080306"))
                setStroke(dp(1), Color.parseColor("#88E0202C"))
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

        val dm = resources.displayMetrics
        val ep = eyeParams
        val p = WindowManager.LayoutParams(
            min(dp(300), dm.widthPixels - dp(16)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = max(dp(8), (dm.widthPixels - dp(300)) / 2)
            y = (ep?.y ?: dp(96)) + dp(70)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
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
                    statusLine.text = if (busy) t("কাজ করছি…") else t("প্রস্তুত")
                }
            }
        }
    }

    private fun sendToAssistant(text: String) {
        val vm = assistant()
        if (vm == null) {
            statusLine.text = t("প্রস্তুত নয়")
            return
        }
        statusLine.text = t("পাঠানো হয়েছে…")
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
    }

    override fun onDestroy() {
        replyJob?.cancel()
        stateJob?.cancel()
        scope?.cancel()
        panel?.let { runCatching { windowManager.removeView(it) } }
        eye?.let { it.stop(); runCatching { windowManager.removeView(it) } }
        panel = null
        eye = null
        ZyraxonViewModel.active?.setOverlayExpanded(false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ view

    /**
     * The eye itself. Everything is drawn here — socket glow, sclera, veins, an
     * iris with fibres, a slit pupil that opens when the assistant hears or
     * speaks, and an eyelid that closes when the microphone is off.
     */
    private class EyeView(context: Context) : View(context) {
        @Volatile var micOn = true
        @Volatile var hearing = false
        @Volatile var speaking = false
        @Volatile var thinking = false
        @Volatile var level = 0f

        /** The cloud's own stage ("স্যান্ডবক্স জাগানো হচ্ছে…"), shown while a turn waits. */
        @Volatile var stage: String = ""
            set(v) {
                if (v.isNotBlank() && v != field) note = v
                field = v
            }

        private var running = true
        private var note: String? = null
        private var noteUntil = 0L
        private val start = SystemClock.uptimeMillis()
        private var lid = 0f      // 0 open .. 1 closed, eased towards its target
        private var look = 0f     // gaze wander

        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val clip = Path()

        fun setNote(text: String) { note = text; noteUntil = SystemClock.uptimeMillis() + 1500 }
        fun stop() { running = false }

        private fun drawCalm(canvas: Canvas, t: Float, cx: Float, cy: Float, r: Float) {
            val light = ai.zyraxon.assistant.ui.theme.Glass.mode == ai.zyraxon.assistant.ui.theme.ZyThemeMode.LIGHT
            val accent = if (light) Color.rgb(91, 61, 245) else Color.rgb(139, 123, 255)
            val core = when {
                !micOn && !speaking && !thinking -> Color.rgb(140, 146, 160)
                speaking -> Color.rgb(55, 214, 197)
                thinking -> Color.rgb(255, 176, 46)
                else -> accent
            }
            val beat = 0.5f + 0.5f * sin(t * (if (hearing || speaking) 5f else 1.8f))
            val gr = r * (0.9f + 0.12f * beat + level * 0.2f)
            glow.shader = RadialGradient(cx, cy, gr,
                intArrayOf(Color.argb(140, Color.red(core), Color.green(core), Color.blue(core)),
                    Color.argb(50, Color.red(core), Color.green(core), Color.blue(core)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, gr, glow)
            fill.shader = RadialGradient(cx - r * 0.15f, cy - r * 0.2f, r * 0.7f,
                intArrayOf(Color.WHITE, core), floatArrayOf(0.05f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * (0.5f + 0.03f * beat), fill)
            fill.shader = null
            line.color = Color.argb(200, 255, 255, 255)
            line.strokeWidth = r * 0.05f
            canvas.drawCircle(cx, cy, r * 0.5f, line)
            if (note != null && SystemClock.uptimeMillis() < noteUntil) {
                fill.color = if (light) Color.BLACK else Color.WHITE
                fill.textSize = r * 0.3f
                fill.textAlign = Paint.Align.CENTER
                canvas.drawText(note!!, cx, cy * 2f - 2f, fill)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            val t = (now - start) / 1000f
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = min(w, h) / 2f

            // Only the horror look wears the watching eye. Light and dark get a calm
            // glowing orb in the theme's own colour, so nothing frightening shows up.
            if (ai.zyraxon.assistant.ui.theme.Glass.mode != ai.zyraxon.assistant.ui.theme.ZyThemeMode.HORROR) {
                drawCalm(canvas, t, cx, cy, r)
                if (running) postInvalidateOnAnimation()
                return
            }

            // Colour follows the state: blood red when idle, white-hot while hearing,
            // amber while thinking, violet-red while speaking, grey when muted.
            val core = when {
                !micOn && !speaking && !thinking -> Color.rgb(90, 80, 84)
                speaking -> Color.rgb(255, 60, 140)
                thinking -> Color.rgb(255, 150, 30)
                hearing -> Color.rgb(255, 40, 40)
                else -> Color.rgb(200, 20, 30)
            }

            // Pulsing glow behind the eye.
            val beat = 0.5f + 0.5f * sin(t * (if (hearing || speaking) 6.5f else 2.2f))
            val gr = r * (0.92f + 0.16f * beat + level * 0.25f)
            glow.shader = RadialGradient(
                cx, cy, gr,
                intArrayOf(Color.argb((150 * (0.55f + 0.45f * beat)).toInt(), Color.red(core), Color.green(core), Color.blue(core)),
                    Color.argb(60, Color.red(core), Color.green(core), Color.blue(core)), Color.TRANSPARENT),
                floatArrayOf(0.35f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, gr, glow)
            glow.shader = null

            // Blink and the muted eyelid.
            val phase = t % 4.3f
            val blink = if (phase > 4.1f) sin((phase - 4.1f) / 0.2f * Math.PI.toFloat()) else 0f
            val target = if (micOn || speaking || thinking) blink else max(0.82f, blink)
            lid += (target - lid) * 0.25f

            // Gaze wanders slowly while idle and locks on centre while hearing.
            val wander = if (hearing || speaking) 0f else sin(t * 0.7f) * 0.5f + sin(t * 1.9f) * 0.25f
            look += (wander - look) * 0.1f

            val ew = r * 1.7f            // eye width
            val eh = r * 1.05f * (1f - lid * 0.92f) + r * 0.04f
            // Almond eye shape as the clip.
            clip.reset()
            clip.moveTo(cx - ew / 2, cy)
            clip.quadTo(cx, cy - eh * 1.25f, cx + ew / 2, cy)
            clip.quadTo(cx, cy + eh * 1.25f, cx - ew / 2, cy)
            clip.close()

            canvas.save()
            canvas.clipPath(clip)

            // Sclera: dark, bloodshot.
            fill.shader = RadialGradient(
                cx, cy, ew / 2,
                intArrayOf(Color.rgb(250, 236, 232), Color.rgb(222, 150, 150), Color.rgb(90, 14, 18)),
                floatArrayOf(0.2f, 0.72f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.shader = null

            // Veins creeping in from the corners, twitching.
            line.color = Color.argb(170, 150, 10, 20)
            line.strokeWidth = max(1f, r * 0.035f)
            for (k in 0 until 7) {
                val ang = k * 0.9f + 0.3f
                val side = if (k % 2 == 0) -1f else 1f
                val sx = cx + side * ew / 2
                val ex = cx + side * (ew * (0.2f + 0.04f * sin(t * 1.5f + k)))
                val ey = cy + sin(ang) * eh * 0.6f
                canvas.drawLine(sx, cy + cos(ang) * eh * 0.15f, ex, ey, line)
            }

            // Iris.
            val ir = r * 0.52f * (1f + level * 0.12f)
            val ix = cx + look * r * 0.32f
            val iy = cy
            fill.shader = RadialGradient(
                ix, iy, ir,
                intArrayOf(Color.rgb(25, 0, 3), core, Color.rgb(Color.red(core) / 2, Color.green(core) / 2, Color.blue(core) / 2)),
                floatArrayOf(0.15f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(ix, iy, ir, fill)
            fill.shader = null

            // Iris fibres.
            line.color = Color.argb(150, 255, 190, 150)
            line.strokeWidth = max(1f, r * 0.02f)
            for (k in 0 until 18) {
                val a = k / 18f * 6.2832f + t * 0.15f
                canvas.drawLine(
                    ix + cos(a) * ir * 0.34f, iy + sin(a) * ir * 0.34f,
                    ix + cos(a) * ir * 0.96f, iy + sin(a) * ir * 0.96f, line
                )
            }

            // Slit pupil: thin when idle, dilates with the voice.
            val open = when {
                hearing -> 0.55f + level * 0.4f
                speaking -> 0.45f + level * 0.5f
                thinking -> 0.3f + 0.25f * beat
                else -> 0.16f
            }
            val pw = ir * open * 0.55f
            val ph = ir * 0.92f
            fill.color = Color.BLACK
            canvas.drawOval(ix - pw, iy - ph, ix + pw, iy + ph, fill)

            // Wet highlight.
            fill.color = Color.argb(200, 255, 255, 255)
            canvas.drawCircle(ix - ir * 0.3f, iy - ir * 0.34f, ir * 0.14f, fill)
            canvas.restore()

            // Lash line.
            line.color = Color.argb(240, 20, 0, 4)
            line.strokeWidth = max(2f, r * 0.07f)
            canvas.drawPath(clip, line)

            // Tiny note such as "not ready".
            if (note != null && now < noteUntil) {
                fill.color = Color.WHITE
                fill.textSize = r * 0.3f
                fill.textAlign = Paint.Align.CENTER
                canvas.drawText(note!!, cx, h - 2f, fill)
            }

            if (running) postInvalidateOnAnimation()
        }
    }

    companion object {
        /** How often the eye checks that the bound conversation is still awake. */
        private const val HEARTBEAT_MS = 90_000L

        fun start(context: Context) {
            val intent = Intent(context, OverlayChatService::class.java)
            // The service promotes itself to foreground at once; from a visible
            // app that is allowed. From anywhere else fall back to a plain start.
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Exception) {
                runCatching { context.startService(intent) }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayChatService::class.java)) }
        }
    }
}
