package mg.acchadu.netspeed

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Service sans UI d'application : echantillonne TrafficStats + la RAM et rend les valeurs
 * dans la barre d'etat, sous forme d'icones de notification generees a la volee
 * (Icon.createWithBitmap). Deux notifications, donc deux icones :
 *  - debit (notification du foreground service) : total ↓+↑, detail dans le panneau,
 *  - RAM : pourcentage utilise, detail dans le panneau.
 * Chacune a son propre canal, desactivable independamment dans les reglages.
 *
 * TrafficStats.getTotalRxBytes()/getTotalTxBytes() = compteurs cumules depuis le boot,
 * toutes interfaces confondues (mobile + wifi + usb tethering), loopback exclu.
 * Contrairement aux compteurs per-UID, ils restent lisibles sans permission sur
 * Android 7+. ActivityManager.getMemoryInfo() ne demande pas de permission non plus.
 */
class BandwidthService : Service() {

    private lateinit var nm: NotificationManager
    private lateinit var am: ActivityManager
    private lateinit var pm: PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private val memInfo = ActivityManager.MemoryInfo()

    private var lastRx = 0L
    private var lastTx = 0L
    private var lastTs = 0L
    private var running = false

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE // seul l'alpha compte : le systeme teinte l'icone
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val tick = object : Runnable {
        override fun run() {
            // Ecran eteint : rien n'est visible, on ne poste pas de notification.
            // On recale les compteurs pour que le premier echantillon au reveil soit juste.
            if (pm.isInteractive) sample() else resetCounters()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        am = getSystemService(ActivityManager::class.java)
        pm = getSystemService(PowerManager::class.java)
        createChannels()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            resetCounters()
            handler.postDelayed(tick, INTERVAL_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        running = false
        nm.cancel(NOTIF_RAM_ID)
        super.onDestroy()
    }

    // --- Notifications ------------------------------------------------------

    private fun createChannels() {
        // Ancien canal v1 en IMPORTANCE_MIN : pas d'icone en barre d'etat. L'importance d'un
        // canal existant ne peut pas etre relevee par l'app, d'ou de nouveaux IDs.
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        // IMPORTANCE_LOW = minimum pour avoir une icone dans la barre d'etat (MIN la masque),
        // toujours sans son ni heads-up.
        nm.createNotificationChannels(listOf(
            channel(CHANNEL_NET_ID, "Debit reseau"),
            channel(CHANNEL_RAM_ID, "Memoire RAM"),
        ))
    }

    private fun channel(id: String, name: String) =
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIF_NET_ID,
            netNotification(0L, 0L),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun netNotification(down: Long, up: Long): Notification {
        val (value, unit) = splitRate(down + up)
        return baseBuilder(CHANNEL_NET_ID)
            .setSmallIcon(textIcon(value, unit))
            .setContentTitle("↓ ${fmtRate(down)}   ↑ ${fmtRate(up)}")
            .setContentText("Debit reseau (total en barre d'etat)")
            .build()
    }

    private fun ramNotification(used: Long, total: Long): Notification {
        val pct = if (total > 0) (used * 100.0 / total).roundToInt() else 0
        return baseBuilder(CHANNEL_RAM_ID)
            .setSmallIcon(textIcon("$pct%", "RAM"))
            .setContentTitle("RAM  ${fmtGiB(used)} / ${fmtGiB(total)} Go  ($pct%)")
            .setContentText("Disponible : ${fmtGiB(total - used)} Go")
            // Pas une notification de FGS : si le process est tue sans onDestroy, elle
            // disparait d'elle-meme au lieu de laisser une valeur figee.
            .setTimeoutAfter(INTERVAL_MS * 5)
            .build()
    }

    private fun baseBuilder(channelId: String): Notification.Builder =
        Notification.Builder(this, channelId)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_STATUS)
            // Groupes distincts : evite que le systeme regroupe les deux notifications.
            .setGroup(channelId)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }

    /**
     * Icone de barre d'etat sur deux lignes (valeur en haut, unite en bas).
     * Le systeme n'utilise que le canal alpha et teinte selon le theme.
     */
    private fun textIcon(top: String, bottom: String): Icon {
        val size = (ICON_DP * resources.displayMetrics.density).roundToInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        drawFitted(c, top, cx, size * 0.60f, size * 0.62f, size.toFloat())
        drawFitted(c, bottom, cx, size * 0.98f, size * 0.40f, size.toFloat())
        return Icon.createWithBitmap(bmp)
    }

    private fun drawFitted(c: Canvas, s: String, cx: Float, baseline: Float, maxSize: Float, maxWidth: Float) {
        iconPaint.textSize = maxSize
        val w = iconPaint.measureText(s)
        if (w > maxWidth) iconPaint.textSize = maxSize * maxWidth / w
        c.drawText(s, cx, baseline, iconPaint)
    }

    // --- Echantillonnage ----------------------------------------------------

    private fun resetCounters() {
        lastRx = TrafficStats.getTotalRxBytes()
        lastTx = TrafficStats.getTotalTxBytes()
        lastTs = SystemClock.elapsedRealtime()
    }

    private fun sample() {
        sampleNet()
        sampleRam()
    }

    private fun sampleNet() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        if (rx == unsupported || tx == unsupported) return
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTs).coerceAtLeast(1L)

        val down = ((rx - lastRx).coerceAtLeast(0L) * 1000.0 / dt).roundToLong()
        val up = ((tx - lastTx).coerceAtLeast(0L) * 1000.0 / dt).roundToLong()

        lastRx = rx; lastTx = tx; lastTs = now
        nm.notify(NOTIF_NET_ID, netNotification(down, up))
    }

    private fun sampleRam() {
        am.getMemoryInfo(memInfo)
        nm.notify(NOTIF_RAM_ID, ramNotification(memInfo.totalMem - memInfo.availMem, memInfo.totalMem))
    }

    /** Valeur courte (<= 3-4 caracteres) + unite, pour l'icone. */
    private fun splitRate(bytesPerSec: Long): Pair<String, String> = when {
        bytesPerSec < 1_000 -> bytesPerSec.toString() to "B/s"
        bytesPerSec < 1_000 * 1024 -> (bytesPerSec / 1024.0).roundToLong().toString() to "KB/s"
        else -> {
            val mb = bytesPerSec / 1_048_576.0
            (if (mb < 10) String.format(Locale.US, "%.1f", mb) else mb.roundToLong().toString()) to "MB/s"
        }
    }

    private fun fmtRate(bytesPerSec: Long): String {
        val (v, u) = splitRate(bytesPerSec)
        return "$v $u"
    }

    private fun fmtGiB(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / 1_073_741_824.0)

    companion object {
        const val ACTION_STOP = "mg.acchadu.netspeed.STOP"
        private const val LEGACY_CHANNEL_ID = "netspeed_fgs"
        private const val CHANNEL_NET_ID = "netspeed_net"
        private const val CHANNEL_RAM_ID = "netspeed_ram"
        private const val NOTIF_NET_ID = 1001
        private const val NOTIF_RAM_ID = 1002
        private const val INTERVAL_MS = 1000L
        private const val ICON_DP = 24f

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BandwidthService::class.java))
        }
    }
}
