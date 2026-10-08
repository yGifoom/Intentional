package org.intentional.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import org.intentional.shared.Stage
import org.intentional.shared.ProtectedApp
import org.intentional.shared.ForegroundWindow
import org.intentional.shared.WindowKind
import org.intentional.shared.foregroundWindowIndex

/** Supports Instagram and YouTube; retain component name so upgrades preserve enabled access.
 * Reads only the focused window root's package name. Never reads node text or children. */
class InstagramAccessibilityService : AccessibilityService() {
    private val engine get() = (application as IntentionalApplication).engine
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = ""
    private var warnedSegment: Pair<Long, Int>? = null
    private var lastPrompt = 0L
    private var banner: LinearLayout? = null
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            foreground = ""
            engine.foregroundChanged(null)
            removeBanner()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            observeForeground()
            engine.tick()
            val state = engine.current
            val session = state.session
            if (session != null && !session.demo) {
                val segment = session.id to session.extensions.size
                if (state.stage == Stage.ACTIVE && session.warned && warnedSegment != segment) {
                    warnedSegment = segment
                    notifyWarning()
                    if (foreground == session.app.androidPackage) showBanner()
                }
                if (state.stage != Stage.ACTIVE) {
                    removeBanner()
                    getSystemService(NotificationManager::class.java).cancel(1)
                }
            } else {
                removeBanner()
                getSystemService(NotificationManager::class.java).cancel(1)
            }
            // Retry pending prompts if a rapid app switch hit prompt()'s debounce.
            if (!state.demo && ProtectedApp.fromPackage(foreground) != null && state.stage in listOf(
                    Stage.GATE, Stage.INTENTION, Stage.DURATION, Stage.EXPIRED, Stage.EXTEND, Stage.REFLECT)) prompt()
            handler.postDelayed(this, 500)
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("check_ins", "Session reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A reminder with one minute left in your Instagram or YouTube session"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType !in listOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED)) return
        // The sender may be a toast, notification, or banner, not the foreground app.
        observeForeground()
    }
    @Suppress("DEPRECATION") // Recycling is needed on our older supported Android releases.
    private fun focusedPackage(): String? {
        if (!getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val visible = windows
        try {
            val index = foregroundWindowIndex(visible.map { window ->
                ForegroundWindow(when (window.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
                    AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.KEYBOARD
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowKind.ACCESSIBILITY_OVERLAY
                    else -> WindowKind.OTHER
                }, window.isFocused, window.isActive)
            }) ?: return null
            val root = visible[index].root ?: return null
            return try { root.packageName?.toString() } finally { root.recycle() }
        } finally { visible.forEach { it.recycle() } }
    }
    private fun observeForeground() {
        val pkg = runCatching { focusedPackage() }.getOrNull().orEmpty()
        val targetApp = ProtectedApp.fromPackage(pkg)
        val previous = foreground
        foreground = pkg
        if (pkg != engine.current.session?.app?.androidPackage) removeBanner()
        val state = engine.current
        if (state.demo && state.stage != Stage.HOME && state.stage != Stage.SAVED) return
        // Polling also detects returns with no new window-state event (idle screens,
        // dismissing system panels, unlocking, or restarting the accessibility service).
        engine.foregroundChanged(targetApp)
        if (targetApp != null) {
            if (previous != pkg) engine.recordOpening(targetApp)
            if (engine.visit(targetApp)) prompt()
        }
    }
    private fun prompt() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPrompt < 1200) return
        lastPrompt = now
        // Accessibility services are eligible to start an activity for user-facing assistance.
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
    private fun notifyWarning() {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = android.app.Notification.Builder(this, "check_ins")
            .setSmallIcon(R.drawable.ic_intentional).setContentTitle("1 minute left on ${engine.current.app.label}")
            .setContentText("Still doing what you came for? Tap to check in.")
            .setContentIntent(intent).setAutoCancel(true).setTimeoutAfter(60_000)
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE).build()
        // Notification permission is optional: the accessibility banner still appears.
        runCatching { getSystemService(NotificationManager::class.java).notify(1, notification) }
    }
    private fun showBanner() {
        removeBanner()
        val padding = (20 * resources.displayMetrics.density).toInt()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply { setColor(Color.rgb(34, 27, 53)); cornerRadius = 24 * resources.displayMetrics.density }
        }
        view.addView(TextView(this).apply { text = "✦  1 minute left on ${engine.current.app.label}"; textSize = 18f; setTextColor(Color.WHITE) })
        view.addView(TextView(this).apply { text = "Still on track? Tap to finish or add more time."; textSize = 14f; setTextColor(Color.rgb(201, 188, 227)); setPadding(0, 12, 0, 0) })
        view.setOnClickListener { removeBanner(); prompt() }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP; y = (36 * resources.displayMetrics.density).toInt()
        }
        runCatching { getSystemService(WindowManager::class.java).addView(view, params); banner = view }
        handler.postDelayed({ if (banner === view) removeBanner() }, 12_000)
    }
    private fun removeBanner() {
        banner?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        banner = null
    }
    override fun onInterrupt() { engine.foregroundChanged(null); removeBanner() }
    override fun onDestroy() {
        engine.foregroundChanged(null)
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        handler.removeCallbacksAndMessages(null)
        removeBanner()
        getSystemService(NotificationManager::class.java).cancel(1)
        super.onDestroy()
    }
}
