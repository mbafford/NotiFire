package com.mbafford.notifire

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    // Bumped on every resume so the screen re-reads rules, recent notifications and access state.
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = colors) { App(resumeTick.intValue) }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object PickApp : Screen
    data class Edit(val rule: Rule, val isNew: Boolean) : Screen
}

@Composable
private fun App(resumeTick: Int) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var rules by remember { mutableStateOf(Store.rules(ctx)) }
    var captureTick by remember { mutableIntStateOf(0) }
    val recent = remember(resumeTick, screen, rules, captureTick) {
        Store.captureActive(ctx) // expires capture (and prunes) if its time is up
        Store.recent(ctx)
    }
    val hasAccess = remember(resumeTick) {
        ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)
    }
    LaunchedEffect(resumeTick) { rules = Store.rules(ctx) }

    fun save(newRules: List<Rule>) {
        rules = newRules
        Store.saveRules(ctx, newRules)
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (val s = screen) {
                Screen.Home -> Home(
                    rules, recent, hasAccess,
                    onCaptureChange = { captureTick++ },
                    onToggle = { r, on -> save(rules.map { if (it.id == r.id) it.copy(enabled = on) else it }) },
                    onEdit = { screen = Screen.Edit(it, isNew = false) },
                    onAdd = { screen = Screen.PickApp },
                    onFromRecent = {
                        screen = Screen.Edit(Rule(pkg = it.pkg, appLabel = it.appLabel, channelId = it.channelId), isNew = true)
                    },
                )
                Screen.PickApp -> AppPicker(
                    onPick = { pkg, label -> screen = Screen.Edit(Rule(pkg = pkg, appLabel = label), isNew = true) },
                    onCancel = { screen = Screen.Home },
                )
                is Screen.Edit -> RuleEditor(
                    s.rule, s.isNew, recent.filter { it.pkg == s.rule.pkg },
                    onSave = { r ->
                        save(if (s.isNew) rules + r else rules.map { if (it.id == r.id) r else it })
                        screen = Screen.Home
                    },
                    onDelete = {
                        save(rules.filterNot { it.id == s.rule.id })
                        screen = Screen.Home
                    },
                    onCancel = { screen = Screen.Home },
                )
            }
        }
    }
}

@Composable
private fun Home(
    rules: List<Rule>,
    recent: List<Seen>,
    hasAccess: Boolean,
    onCaptureChange: () -> Unit,
    onToggle: (Rule, Boolean) -> Unit,
    onEdit: (Rule) -> Unit,
    onAdd: () -> Unit,
    onFromRecent: (Seen) -> Unit,
) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("NotiFire", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
            Text(
                "Matching notifications ring on the alarm channel, even in silent mode.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (!hasAccess) item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Notification access is off", style = MaterialTheme.typography.titleMedium)
                    Text("NotiFire can't see notifications until you allow it.")
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("Open settings") }
                }
            }
        }

        item { SilentTestSwitch() }
        item { CaptureSwitch(onCaptureChange) }

        item { SectionHeader("Rules") }
        if (rules.isEmpty()) item {
            Text("No rules yet.", modifier = Modifier.padding(horizontal = 16.dp))
        }
        items(rules, key = { it.id }) { r ->
            ListItem(
                leadingContent = { AppIcon(r.pkg) },
                headlineContent = { Text(r.appLabel) },
                supportingContent = { Text(r.summary(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Switch(r.enabled, onCheckedChange = { onToggle(r, it) }) },
                modifier = Modifier.clickable { onEdit(r) },
            )
        }
        item {
            Button(onClick = onAdd, modifier = Modifier.padding(16.dp)) { Text("Add rule") }
        }

        item {
            SectionHeader("Recent notifications")
            Text(
                if (Store.captureActive(ctx)) "Capturing from all apps. Tap one to make a rule from it."
                else "From apps with rules. Turn on capture mode to see other apps.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        items(recent) { n ->
            ListItem(
                leadingContent = { AppIcon(n.pkg) },
                headlineContent = { Text(n.title.ifBlank { n.appLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        "${n.appLabel} · ${n.channelId} · ${time(n.postedAt)}\n${n.text}",
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.clickable { onFromRecent(n) },
            )
        }
    }
}

@Composable
private fun AppPicker(onPick: (String, String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    val shown = apps.filter { (pkg, label) ->
        query.isBlank() || label.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true)
    }

    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Text("Pick an app", style = MaterialTheme.typography.titleLarge)
    }
    OutlinedTextField(
        query, { query = it },
        label = { Text("Search") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    LazyColumn(Modifier.fillMaxSize()) {
        items(shown, key = { it.first }) { (pkg, label) ->
            ListItem(
                leadingContent = { AppIcon(pkg) },
                headlineContent = { Text(label) },
                supportingContent = { Text(pkg) },
                modifier = Modifier.clickable { onPick(pkg, label) },
            )
        }
    }
}

@Composable
private fun RuleEditor(
    rule: Rule,
    isNew: Boolean,
    examples: List<Seen>,
    onSave: (Rule) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    var channel by remember { mutableStateOf(rule.channelId) }
    var pattern by remember { mutableStateOf(rule.pattern) }
    var duration by remember { mutableStateOf(rule.durationSec.toString()) }
    var sound by remember { mutableStateOf(rule.soundSec.toString()) }
    var autoPress by remember { mutableStateOf(rule.autoPress) }
    var soundUri by remember { mutableStateOf(rule.soundUri) }
    var volumePct by remember { mutableIntStateOf(rule.volumePct) }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked = res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        soundUri = when {
            picked == null -> Rule.NO_SOUND // picker's "None"
            RingtoneManager.isDefault(picked) -> ""
            else -> picked.toString()
        }
    }
    var enabled by remember { mutableStateOf(rule.enabled) }
    val draft = rule.copy(
        channelId = channel.trim(),
        pattern = pattern.trim(),
        durationSec = duration.toIntOrNull()?.coerceIn(5, 600) ?: 60,
        soundSec = sound.toIntOrNull()?.coerceIn(1, 60) ?: 3,
        autoPress = autoPress.trim(),
        soundUri = soundUri,
        volumePct = volumePct,
        enabled = true, // preview and test ignore the on/off switch
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(rule.pkg, 48.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(rule.appLabel, style = MaterialTheme.typography.titleLarge)
                Text(rule.pkg, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.size(16.dp))
        OutlinedTextField(
            channel, { channel = it },
            label = { Text("Channel ID (blank = any)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            pattern, { pattern = it },
            label = { Text("Title/text contains (regex, blank = any)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            duration, { duration = it.filter(Char::isDigit) },
            label = { Text("Keep alert on screen up to (seconds)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        ListItem(
            headlineContent = { Text("Sound") },
            supportingContent = { Text(remember(soundUri) { Rule.soundTitle(ctx, soundUri) }) },
            trailingContent = {
                TextButton(onClick = {
                    pickSound.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alert sound")
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Rule.defaultAlarm(ctx))
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            .putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                when (soundUri) {
                                    Rule.NO_SOUND -> null
                                    "" -> Settings.System.DEFAULT_ALARM_ALERT_URI
                                    else -> Uri.parse(soundUri)
                                },
                            )
                    )
                }) { Text("Choose") }
            },
        )
        Text("Volume: $volumePct% of alarm max", modifier = Modifier.padding(top = 8.dp))
        Slider(
            value = volumePct.toFloat(),
            onValueChange = { volumePct = it.roundToInt() },
            valueRange = 10f..100f,
            steps = 8,
        )
        OutlinedTextField(
            sound, { sound = it.filter(Char::isDigit) },
            label = { Text("Cut off sound after (seconds)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            autoPress, { autoPress = it },
            label = { Text("Auto-press button first (regex, optional)") },
            supportingText = { Text("Pressed before alerting; the updated notification is what alerts. Never presses approve/allow/accept.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Switch(enabled, onCheckedChange = { enabled = it })
            Text("Enabled", modifier = Modifier.padding(start = 12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(draft.copy(enabled = enabled)) }) { Text("Save") }
            OutlinedButton(onClick = {
                Alerter.start(ctx, draft.copy(durationSec = 10), "test-${rule.id}", "Test alert", silent = true)
            }) { Text("Silent test") }
            if (!isNew) TextButton(onClick = onDelete) { Text("Delete") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        SectionHeader("Recent from this app")
        if (examples.isEmpty()) Text("None seen yet. Ones that arrive after access is on will show up here.")
        examples.forEach { n ->
            val hit = draft.matches(n)
            Row(Modifier.padding(vertical = 6.dp).clickable { channel = n.channelId }) {
                Text(if (hit) "✓" else "✗", modifier = Modifier.padding(end = 12.dp))
                Column {
                    Text(n.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.bodyLarge)
                    Text("${n.channelId} · ${time(n.postedAt)}", style = MaterialTheme.typography.bodySmall)
                    if (n.actions.isNotEmpty()) Text(
                        "Buttons: " + n.actions.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (n.text.isNotBlank()) Text(n.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider()
        }
        if (examples.isNotEmpty()) Text(
            "✓ = this rule would ring. Tap one to use its channel.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun CaptureSwitch(onChange: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Store.captureActive(ctx)) }
    ListItem(
        headlineContent = { Text("Capture mode") },
        supportingContent = {
            Text(
                if (on) "Recording notifications from all apps until ${time(Store.captureUntil(ctx))}"
                else "Record notifications from all apps for ${Store.CAPTURE_MS / 60_000} minutes, to make new rules"
            )
        },
        trailingContent = {
            Switch(on, onCheckedChange = {
                Store.setCapture(ctx, it)
                on = Store.captureActive(ctx)
                onChange()
            })
        },
    )
}

@Composable
private fun SilentTestSwitch() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Store.silentTestActive(ctx)) }
    ListItem(
        headlineContent = { Text("Silent test mode") },
        supportingContent = {
            Text(
                if (on) "Alerts are silent until ${time(Store.silentUntil(ctx))}"
                else "Make all alerts silent for ${Store.SILENT_TEST_MS / 60_000} minutes"
            )
        },
        trailingContent = {
            Switch(on, onCheckedChange = {
                Store.setSilentTest(ctx, it)
                on = Store.silentTestActive(ctx)
            })
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val icon = remember(pkg) { loadIcon(ctx, pkg) }
    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(size))
    else Spacer(Modifier.size(size))
}

private fun loadIcon(ctx: Context, pkg: String) = runCatching {
    ctx.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap()
}.getOrNull()

private fun Rule.summary(): String = listOfNotNull(
    channelId.ifBlank { null }?.let { "channel $it" },
    pattern.ifBlank { null }?.let { "matches \"$it\"" },
    autoPress.ifBlank { null }?.let { "auto-press \"$it\"" },
    "volume $volumePct%, on screen ${durationSec}s",
).joinToString(" · ").let { if (channelId.isBlank() && pattern.isBlank()) "any notification · $it" else it }

private fun time(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
