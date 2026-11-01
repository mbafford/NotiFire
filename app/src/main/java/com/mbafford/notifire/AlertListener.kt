package com.mbafford.notifire

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class AlertListener : NotificationListenerService() {

    override fun onListenerConnected() {
        if (!Store.captureActive(this)) Store.pruneRecent(this)
        // Seed the recent list with whatever is already showing (from apps we care about).
        activeNotifications?.forEach { record(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val seen = record(sbn) ?: return
        val actions = sbn.notification.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() && it.actionIntent != null }
            .map { it.title.toString() to it.actionIntent }

        // The notification we're already alerting on changed (e.g. new buttons): refresh the alert.
        if (Alerter.isActive(sbn.key)) {
            Alerter.update(sbn.key, seen.title, seen.text, sbn.notification.contentIntent, actions)
            return
        }

        val rule = Store.rules(this).firstOrNull { it.matches(seen) } ?: return
        val alert = {
            if (Alerter.shouldAlert(sbn.key)) {
                Alerter.start(this, rule, sbn.key, seen.title, seen.text, sbn.notification.contentIntent, actions)
            }
        }

        // Press a "show me the real buttons" button first and alert on the updated notification.
        // Only once per notification; if no update arrives soon, alert on this version anyway.
        val target = rule.autoPressTarget(actions.map { it.first })
        if (target != null && Alerter.markAutoPressed(sbn.key)) {
            val sent = runCatching { actions.first { it.first == target }.second.send() }
                .onFailure { Log.w("NotiFire", "auto-press \"$target\" failed", it) }
                .isSuccess
            Log.i("NotiFire", "auto-pressed \"$target\" on ${sbn.packageName}: sent=$sent")
            if (sent) {
                Alerter.alertIfNoUpdate(sbn.key, alert)
                return
            }
        }
        Alerter.cancelPendingAlert(sbn.key)
        alert()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Approved/denied/dismissed in the source app: stop ringing.
        Alerter.sourceRemoved(this, sbn.key)
    }

    private fun record(sbn: StatusBarNotification): Seen? {
        if (sbn.packageName == packageName) return null
        // Apps without a rule are ignored outright unless capture mode is on.
        if (!Store.wanted(this, sbn.packageName)) return null
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        return sbn.toSeen(this).also { Store.addRecent(this, it) }
    }
}

fun StatusBarNotification.toSeen(ctx: Context): Seen {
    val e = notification.extras
    val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
    val text = listOfNotNull(
        (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
        lines,
        e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
    ).filter { it.isNotBlank() }.joinToString("\n")
    val actions = notification.actions.orEmpty().map { it.title.toString() }
    return Seen(packageName, appLabel(ctx, packageName), notification.channelId.orEmpty(), title, text, postTime, actions)
}

fun appLabel(ctx: Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
