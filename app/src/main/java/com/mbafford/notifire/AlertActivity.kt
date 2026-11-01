package com.mbafford.notifire

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** Full-screen alert shown over the lock screen (showWhenLocked / turnScreenOn in the manifest). */
class AlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Alerter.active == null) return finish()
        Alerter.onStopped = { runOnUiThread { finish() } }

        setContent {
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    // Read inside composition so the screen redraws when the notification updates.
                    val alert = Alerter.active ?: return@Surface
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val icon = remember(alert.rule.pkg) {
                            runCatching {
                                packageManager.getApplicationIcon(alert.rule.pkg).toBitmap(192, 192).asImageBitmap()
                            }.getOrNull()
                        }
                        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(96.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(alert.rule.appLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            alert.title.ifBlank { "Notification" },
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                        )
                        if (alert.text.isNotBlank()) Text(
                            alert.text,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            maxLines = 6,
                        )
                        if (alert.silent) Text(
                            "Silent test",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Spacer(Modifier.height(48.dp))
                        alert.actions.forEach { (label, pi) ->
                            FilledTonalButton(
                                onClick = { press(label, pi) },
                                modifier = Modifier.fillMaxWidth().height(64.dp).padding(bottom = 8.dp),
                            ) { Text(label, style = MaterialTheme.typography.titleMedium) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = ::open, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                            Text(
                                if (alert.contentIntent != null) "Open notification" else "Open ${alert.rule.appLabel}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = ::dismiss, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text("Stop")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Alerter.onStopped = null
        super.onDestroy()
    }

    private fun dismiss() {
        Alerter.stop(this)
        finish()
    }

    /**
     * Do what tapping the source notification would have done. Falls back to launching the
     * app if the notification has no tap action or it can't be sent (e.g. already cancelled).
     */
    private fun open() = afterUnlock(close = true) {
        val alert = Alerter.active ?: return@afterUnlock
        if (alert.contentIntent == null || !send(alert.contentIntent, "content")) {
            packageManager.getLaunchIntentForPackage(alert.rule.pkg)?.let(::startActivity)
        }
    }

    /**
     * Press one of the source notification's buttons, e.g. a two-factor app's Approve. The alert stays up
     * briefly: if the app swaps in new buttons they appear here; if it removes the
     * notification, the alert closes.
     */
    private fun press(label: String, pi: PendingIntent) = afterUnlock(close = false) {
        send(pi, "action \"$label\"")
        Alerter.closeSoon()
    }

    /**
     * Always unlock first (fingerprint/face/PIN prompt), so the alert can't be used to
     * act on a notification from a locked phone. Then run [then], and close the alert if [close].
     */
    private fun afterUnlock(close: Boolean, then: () -> Unit) {
        Alerter.stopSound(this)
        val go = { then(); if (close) dismiss() }
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return go()
        km.requestDismissKeyguard(this as Activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = go()
        })
    }

    private fun send(pi: PendingIntent, what: String): Boolean {
        // We're the visible activity, so we may let the other app's activity start.
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= 36) {
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
            } else {
                @Suppress("DEPRECATION")
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
        }
        return runCatching {
            pi.send(this, 0, null, null, null, null, options.toBundle())
            Log.i("NotiFire", "sent $what for ${pi.creatorPackage}")
        }.onFailure { Log.w("NotiFire", "sending $what failed", it) }.isSuccess
    }
}
