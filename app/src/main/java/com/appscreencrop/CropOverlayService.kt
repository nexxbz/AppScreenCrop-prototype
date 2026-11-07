package com.appscreencrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/** orientation: 0 = automática (sigue la rotación), 1 = horizontal, 2 = vertical. scale: 0.3..1 del área máxima. */
data class CropConfig(val orientation: Int, val ratio: Float, val scale: Float, val target: String? = null)

/** Dibuja hasta 4 barras negras (izq, arriba, der, abajo) sobre todas las apps. */
class CropOverlayService : Service() {

    private lateinit var wm: WindowManager
    private val bars = arrayOfNulls<View>(4)
    private var cfg = CropConfig(0, 16f / 10f, 1f)
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var visible = false
    private var currentFg: String? = null
    private var lastQuery = 0L
    private val poll = object : Runnable {
        override fun run() {
            pollForeground()
            handler.postDelayed(this, 600)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeBars()
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        cfg = CropConfig(
            intent?.getIntExtra(EXTRA_ORIENT, 0) ?: 0,
            intent?.getFloatExtra(EXTRA_RATIO, 16f / 10f) ?: 16f / 10f,
            intent?.getFloatExtra(EXTRA_SCALE, 1f) ?: 1f,
            intent?.getStringExtra(EXTRA_TARGET)
        )
        if (visible) applyBars()
        if (!polling) {
            polling = true
            handler.post(poll)
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (visible) applyBars()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        polling = false
        visible = false
        removeBars()
        super.onDestroy()
    }

    /** Detecta qué app está en primer plano (acceso de uso) y muestra las barras solo sobre la app elegida. */
    private fun pollForeground() {
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 6 * 3600_000L else lastQuery - 1000L
        lastQuery = now
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) currentFg = e.packageName
            }
        } catch (e: Exception) {
        }
        val show = cfg.target != null && currentFg == cfg.target
        if (show && !visible) {
            visible = true
            applyBars()
        } else if (!show && visible) {
            visible = false
            removeBars()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyBars() {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels
        // Área visible: se conserva la proporción elegida (16:10 por defecto = 1280×800).
        val t = when (cfg.orientation) {
            1 -> cfg.ratio
            2 -> 1f / cfg.ratio
            else -> if (w >= h) cfg.ratio else 1f / cfg.ratio
        }
        val fitW = if (w.toFloat() / h > t) h * t else w.toFloat()
        val fitH = if (w.toFloat() / h > t) h.toFloat() else w / t
        val sc = cfg.scale.coerceIn(0.3f, 1f)
        val boxW = (fitW * sc).roundToInt().coerceIn(1, w)
        val boxH = (fitH * sc).roundToInt().coerceIn(1, h)
        val l = (w - boxW) / 2
        val r = w - boxW - l
        val tp = (h - boxH) / 2
        val bt = h - boxH - tp
        try {
            place(0, 0, 0, l, h)
            place(1, l, 0, boxW, tp)
            place(2, w - r, 0, r, h)
            place(3, l, h - bt, boxW, bt)
        } catch (e: Exception) {
            removeBars()
            stopSelf()
        }
    }

    private fun place(i: Int, x: Int, y: Int, bw: Int, bh: Int) {
        val current = bars[i]
        if (bw <= 0 || bh <= 0) {
            current?.let { wm.removeView(it) }
            bars[i] = null
            return
        }
        val lp = WindowManager.LayoutParams(
            bw, bh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (current == null) {
            val v = View(this).apply { setBackgroundColor(Color.BLACK) }
            bars[i] = v
            wm.addView(v, lp)
        } else {
            wm.updateViewLayout(current, lp)
        }
    }

    private fun removeBars() {
        for (i in bars.indices) {
            bars[i]?.let { runCatching { wm.removeView(it) } }
            bars[i] = null
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recorte activo", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CropOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val quitIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentTitle("AppScreen Crop activo")
            .setContentText("Solo se aplica a la app elegida")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(quitIcon, "Quitar recorte", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val ACTION_STOP = "com.appscreencrop.STOP"
        const val EXTRA_ORIENT = "orient"
        const val EXTRA_RATIO = "ratio"
        const val EXTRA_SCALE = "scale"
        const val EXTRA_TARGET = "target"
        private const val CHANNEL = "crop_overlay"
        private const val NOTIF_ID = 1
    }
}
