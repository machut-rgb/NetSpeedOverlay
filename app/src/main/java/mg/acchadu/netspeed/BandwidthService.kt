package mg.acchadu.netspeed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Service sans UI d'application : echantillonne TrafficStats et rend le debit
 * dans une fenetre overlay (TYPE_APPLICATION_OVERLAY).
 *
 * TrafficStats.getTotalRxBytes()/getTotalTxBytes() = compteurs cumules depuis le boot,
 * toutes interfaces confondues (mobile + wifi + usb tethering), loopback exclu.
 * Contrairement aux compteurs per-UID, ils restent lisibles sans permission sur
 * Android 7+.
 */
class BandwidthService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlay: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private var lastRx = 0L
    private var lastTx = 0L
    private var lastTs = 0L

    private val tick = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlay == null && Settings.canDrawOverlays(this)) {
            attachOverlay()
            resetCounters()
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, INTERVAL_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        super.onDestroy()
    }

    // --- Foreground ---------------------------------------------------------

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Moniteur de debit",
                NotificationManager.IMPORTANCE_MIN // pas de son, pas de heads-up, section silencieuse
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(ch)
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NetSpeed")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    // --- Overlay ------------------------------------------------------------

    private fun attachOverlay() {
        val bg = GradientDrawable().apply {
            setColor(Color.argb(140, 0, 0, 0))
            cornerRadius = dp(6f)
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
            background = bg
            setPadding(dp(5f).toInt(), dp(1f).toInt(), dp(5f).toInt(), dp(1f).toInt())
            text = "· · ·"
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(OVERLAY_X_DP).toInt()
            y = dp(OVERLAY_Y_DP).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        runCatching { windowManager.addView(tv, params) }
            .onSuccess { overlay = tv }
    }

    // --- Echantillonnage ----------------------------------------------------

    private fun resetCounters() {
        lastRx = TrafficStats.getTotalRxBytes()
        lastTx = TrafficStats.getTotalTxBytes()
        lastTs = SystemClock.elapsedRealtime()
    }

    private fun sample() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        if (rx == unsupported || tx == unsupported) {
            overlay?.text = "n/a"
            return
        }
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTs).coerceAtLeast(1L)

        val down = ((rx - lastRx).coerceAtLeast(0L) * 1000.0 / dt).roundToLong()
        val up = ((tx - lastTx).coerceAtLeast(0L) * 1000.0 / dt).roundToLong()

        lastRx = rx; lastTx = tx; lastTs = now
        overlay?.text = "\u2193${fmt(down)}  \u2191${fmt(up)}"
    }

    private fun fmt(bytesPerSec: Long): String = when {
        bytesPerSec < 1_000 -> String.format(Locale.US, "%3dB", bytesPerSec)
        bytesPerSec < 1_000_000 -> String.format(Locale.US, "%3.0fK", bytesPerSec / 1024.0)
        else -> String.format(Locale.US, "%.1fM", bytesPerSec / 1_048_576.0)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        const val ACTION_STOP = "mg.acchadu.netspeed.STOP"
        private const val CHANNEL_ID = "netspeed_fgs"
        private const val NOTIF_ID = 1001
        private const val INTERVAL_MS = 1000L
        private const val OVERLAY_X_DP = 8f
        private const val OVERLAY_Y_DP = 2f

        fun start(ctx: Context) {
            val i = Intent(ctx, BandwidthService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
