package com.mbafford.notifire

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Plays a short burst on the alarm stream (which silent mode and most DND settings leave alone)
 * and shows a full-screen alert over the lock screen. The alert stays up until the rule's time
 * runs out, the source notification goes away, or it is stopped/opened.
 */
object Alerter {
    private const val CHANNEL = "alerts"
    private const val QUIET_CHANNEL = "alert_status"
    private const val NOTIF_ID = 1
    private const val REALERT_AFTER_MS = 2 * 60_000L
    private const val AUTO_PRESS_WAIT_MS = 4_000L
    private const val CLOSE_AFTER_PRESS_MS = 5_000L

    data class Active(
        val rule: Rule,
        val key: String,
        val title: String,
        val text: String,
        val silent: Boolean,
        /** What tapping the source notification does; null if it does nothing. */
        val contentIntent: PendingIntent?,
        /** The notification's own buttons (label to what pressing it sends), minus reply boxes. */
        val actions: List<Pair<String, PendingIntent>> = emptyList(),
    )

    /** Compose state, so the alert screen redraws when the notification updates. */
    var active: Active? by mutableStateOf(null)
        private set

    /** Called when the alert ends, so the full-screen activity can close itself. */
    var onStopped: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var ringtone: Ringtone? = null
    private var savedAlarmVolume: Int? = null
    private val alertedAt = mutableMapOf<String, Long>()
    private val timeout = Runnable { appCtx?.let(::stop) }
    private val soundDone = Runnable { appCtx?.let(::stopSound) }
    private val autoPressedAt = mutableMapOf<String, Long>()
    private val pendingAlerts = mutableMapOf<String, Runnable>()

    fun isActive(key: String) = active?.key == key

    /** The source notification changed while we're showing it: swap in its new text and buttons. */
    @Synchronized
    fun update(key: String, title: String, text: String, contentIntent: PendingIntent?, actions: List<Pair<String, PendingIntent>>) {
        val a = active?.takeIf { it.key == key } ?: return
        handler.removeCallbacks(timeout)
        active = a.copy(title = title, text = text, contentIntent = contentIntent, actions = actions)
        Log.i("NotiFire", "alert updated, buttons=${actions.map { it.first }}")
        handler.postDelayed(timeout, a.rule.durationSec * 1000L)
    }

    /** After a button press, close unless the notification updates or goes away first. */
    fun closeSoon() {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, CLOSE_AFTER_PRESS_MS)
    }

    /** True the first time a notification is auto-pressed, so we never loop pressing it. */
    @Synchronized
    fun markAutoPressed(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        autoPressedAt.values.removeAll { now - it > REALERT_AFTER_MS }
        if (key in autoPressedAt) return false
        autoPressedAt[key] = now
        return true
    }

    /** Run [alert] in a few seconds unless the notification updates first (see [cancelPendingAlert]). */
    @Synchronized
    fun alertIfNoUpdate(key: String, alert: () -> Unit) {
        cancelPendingAlert(key)
        val r = Runnable {
            synchronized(this) { pendingAlerts.remove(key) }
            Log.i("NotiFire", "no update after auto-press, alerting on original")
            alert()
        }
        pendingAlerts[key] = r
        handler.postDelayed(r, AUTO_PRESS_WAIT_MS)
    }

    @Synchronized
    fun cancelPendingAlert(key: String) {
        pendingAlerts.remove(key)?.let(handler::removeCallbacks)
    }

    /** Apps re-post the same notification on updates; only alert once per key every couple of minutes. */
    @Synchronized
    fun shouldAlert(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        alertedAt.values.removeAll { now - it > REALERT_AFTER_MS }
        if (key in alertedAt) return false
        alertedAt[key] = now
        return true
    }

    @Synchronized
    fun start(
        ctx: Context,
        rule: Rule,
        key: String,
        title: String,
        text: String = "",
        contentIntent: PendingIntent? = null,
        actions: List<Pair<String, PendingIntent>> = emptyList(),
        silent: Boolean = Store.silentTestActive(ctx),
    ) {
        val app = ctx.applicationContext
        stop(app)
        appCtx = app
        active = Active(rule, key, title, text, silent, contentIntent, actions)
        Log.i("NotiFire", "alert rule=${rule.id} pkg=${rule.pkg} silent=$silent")

        val uri = rule.sound(app)
        if (!silent && uri != null) {
            val audio = app.getSystemService(AudioManager::class.java)
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val min = audio.getStreamMinVolume(AudioManager.STREAM_ALARM)
            val level = (max * rule.volumePct / 100.0).roundToInt().coerceIn(maxOf(min, 1), max)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, level, 0)
        }

        // A picked sound that's gone (e.g. file deleted) falls back to the default alarm.
        ringtone = uri?.let {
            RingtoneManager.getRingtone(app, it) ?: RingtoneManager.getRingtone(app, Rule.defaultAlarm(app))
        }?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = false // play once; soundSec only cuts off long sounds
            // Silent test still starts a real alarm-stream player, just at zero gain.
            if (silent) volume = 0f
            play()
        }
        if (!silent) app.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )

        // A full-screen notification only takes over the screen when the phone is locked or idle.
        // With "display over other apps" we're allowed to open the alert directly, even mid-use,
        // and the notification is just a quiet status entry with a Stop button.
        val direct = Settings.canDrawOverlays(app)
        showNotification(app, rule, title, silent, direct)
        if (direct) {
            runCatching {
                app.startActivity(Intent(app, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w("NotiFire", "direct alert launch failed", it) }
        }
        handler.postDelayed(soundDone, rule.soundSec * 1000L)
        handler.postDelayed(timeout, rule.durationSec * 1000L)
    }

    @Synchronized
    fun sourceRemoved(ctx: Context, key: String) {
        cancelPendingAlert(key)
        if (key == active?.key) stop(ctx.applicationContext)
    }

    /** Ends the sound and vibration but leaves the alert on screen. */
    @Synchronized
    fun stopSound(ctx: Context) {
        handler.removeCallbacks(soundDone)
        ringtone?.stop()
        ringtone = null
        ctx.getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        savedAlarmVolume?.let {
            ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, it, 0)
        }
        savedAlarmVolume = null
    }

    @Synchronized
    fun stop(ctx: Context) {
        handler.removeCallbacks(timeout)
        stopSound(ctx)
        val wasActive = active != null
        active = null
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        if (wasActive) onStopped?.invoke()
    }

    private fun showNotification(ctx: Context, rule: Rule, title: String, silent: Boolean, direct: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // we play our own sound on the alarm stream
                enableVibration(false)
            }
        )
        // No heads-up banner: it would sit on top of the alert screen we open ourselves.
        nm.createNotificationChannel(
            NotificationChannel(QUIET_CHANNEL, "Alert status", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        // Over the lock screen this launches straight into AlertActivity; when the phone
        // is in use Android shows it as a heads-up notification instead.
        val fullScreen = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, if (direct) QUIET_CHANNEL else CHANNEL)
            .setSmallIcon(R.drawable.ic_notifire_small)
            .setContentTitle("${rule.appLabel}: ${title.ifBlank { "notification" }}")
            .setContentText(
                if (silent) "Silent test: would have rung for ${rule.soundSec}s"
                else "Matched a NotiFire rule"
            )
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .apply { if (!direct) setFullScreenIntent(fullScreen, true) }
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        nm.notify(NOTIF_ID, n)
    }
}

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Alerter.stop(context.applicationContext)
}
