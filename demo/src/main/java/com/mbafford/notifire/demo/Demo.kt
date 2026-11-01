package com.mbafford.notifire.demo

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Uno: a stand-in two-factor app, for testing NotiFire and taking screenshots without a real push.
 * Mimics the two-step pattern some authenticators use: the first notification only has a
 * "Tap To View Actions" button; pressing it re-posts the notification with Approve / Deny.
 *
 *   adb shell am start -n com.mbafford.notifire.demo/.PostActivity --es kind login
 *   adb shell am start -n com.mbafford.notifire.demo/.PostActivity --es kind message
 */
class PostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("kind") ?: "login") {
            "login" -> Demo.postLogin(this, (System.currentTimeMillis() / 1000).toInt(), expanded = false)
            "message" -> Demo.postMessage(this)
        }
        finish()
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Demo.EXPAND -> Demo.postLogin(context, intent.getIntExtra(Demo.ID, 0), expanded = true)
            Demo.APPROVE, Demo.DENY ->
                context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(Demo.ID, 0))
        }
    }
}

object Demo {
    const val EXPAND = "com.mbafford.notifire.demo.EXPAND"
    const val APPROVE = "com.mbafford.notifire.demo.APPROVE"
    const val DENY = "com.mbafford.notifire.demo.DENY"
    const val ID = "id"
    private const val MESSAGE_ID = 2

    /** Each login request gets its own notification [id], like a real push would. */
    fun postLogin(ctx: Context, id: Int, expanded: Boolean) {
        val nm = channels(ctx)
        val b = Notification.Builder(ctx, "login")
            .setSmallIcon(R.drawable.ic_uno_small)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
        if (expanded) {
            b.setContentTitle("Verify your identity")
                .setContentText("Are you signing in to Tyrell Corporation VPN?")
                .addAction(action(ctx, "Approve", APPROVE, id))
                .addAction(action(ctx, "Deny", DENY, id))
        } else {
            b.setContentTitle("Login request")
                .setContentText("Are you signing in to Tyrell Corporation VPN?")
                .addAction(action(ctx, "Tap To View Actions", EXPAND, id))
        }
        nm.notify(id, b.build())
    }

    fun postMessage(ctx: Context) {
        channels(ctx).notify(
            MESSAGE_ID,
            Notification.Builder(ctx, "messages")
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("Alex")
                .setContentText("Dinner at 7?")
                .build(),
        )
    }

    private fun action(ctx: Context, label: String, act: String, id: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            ctx, act.hashCode() xor id,
            Intent(act).setClass(ctx, ActionReceiver::class.java).putExtra(ID, id),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, label, pi).build()
    }

    private fun channels(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("login", "Uno Push requests", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_DEFAULT))
        return nm
    }
}
