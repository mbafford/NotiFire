package com.mbafford.notifire

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One "alert me even on silent" rule. Blank channelId / pattern mean "any". */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val pkg: String,
    val appLabel: String,
    val channelId: String = "",
    val pattern: String = "",
    val durationSec: Int = 60,
    val soundSec: Int = 3,
    val enabled: Boolean = true,
    /** Regex for a notification button to press automatically before alerting, e.g. "Tap To View Actions". */
    val autoPress: String = "",
    /** Sound to play: blank = default alarm, [NO_SOUND] = none, otherwise a content:// URI from the picker. */
    val soundUri: String = "",
    /** Alarm volume while the sound plays, as a percent of max (restored afterwards). */
    val volumePct: Int = 30,
    /** Vibrate first; if nothing is pressed within this many seconds, vibrate again and play the sound. 0 = sound right away. */
    val escalateSec: Int = 5,
    /** How hard to buzz, at the alert and again at escalation. */
    val vibration: Vibration = Vibration.LONG,
) {
    /** The sound to play, or null for none. */
    fun sound(ctx: Context): Uri? = when (soundUri) {
        NO_SOUND -> null
        "" -> defaultAlarm(ctx)
        else -> Uri.parse(soundUri)
    }

    /** The button to auto-press on this notification, if any. Never one that looks like an approval. */
    fun autoPressTarget(labels: List<String>): String? {
        if (autoPress.isBlank()) return null
        val re = runCatching { Regex(autoPress, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        return labels.firstOrNull { re.containsMatchIn(it) && !APPROVAL.containsMatchIn(it) }
    }

    fun matches(n: Seen): Boolean {
        if (!enabled || n.pkg != pkg) return false
        if (channelId.isNotBlank() && channelId != n.channelId) return false
        if (pattern.isBlank()) return true
        // Treat the pattern as a regex; fall back to plain text if it doesn't compile.
        val re = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
        return re?.containsMatchIn(n.matchText) ?: n.matchText.contains(pattern, ignoreCase = true)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("pkg", pkg).put("appLabel", appLabel).put("channelId", channelId)
        .put("pattern", pattern).put("durationSec", durationSec).put("soundSec", soundSec).put("enabled", enabled)
        .put("autoPress", autoPress).put("soundUri", soundUri)
        .put("volumePct", volumePct).put("escalateSec", escalateSec)
        .put("vibration", vibration.name.lowercase())

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            id = o.optString("id", UUID.randomUUID().toString()),
            pkg = o.getString("pkg"),
            appLabel = o.optString("appLabel", o.getString("pkg")),
            channelId = o.optString("channelId"),
            pattern = o.optString("pattern"),
            durationSec = o.optInt("durationSec", 60),
            soundSec = o.optInt("soundSec", 3),
            enabled = o.optBoolean("enabled", true),
            autoPress = o.optString("autoPress"),
            soundUri = o.optString("soundUri"),
            volumePct = o.optInt("volumePct", 30),
            escalateSec = o.optInt("escalateSec", 5),
            vibration = Vibration.parse(o.optString("vibration")),
        )

        const val NO_SOUND = "none"

        fun defaultAlarm(ctx: Context): Uri =
            RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        /** Human-readable name for a rule's sound setting. */
        fun soundTitle(ctx: Context, soundUri: String): String {
            fun title(uri: Uri) = runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull()
            return when (soundUri) {
                NO_SOUND -> "None (screen and vibration only)"
                "" -> "Default alarm" + (title(defaultAlarm(ctx))?.let { " ($it)" } ?: "")
                else -> title(Uri.parse(soundUri)) ?: "Custom sound"
            }
        }

        /** Auto-press must never approve anything on its own; that would defeat 2FA prompts. */
        private val APPROVAL = Regex("approve|allow|accept|confirm|grant|\\byes\\b|verify", RegexOption.IGNORE_CASE)
    }
}

/**
 * Vibration patterns: alternating off/on milliseconds, and the index to repeat from (-1 = play once).
 * A repeating pattern keeps going until a press, the end of the sound, or the alert closes.
 */
enum class Vibration(val label: String, val timings: LongArray, val repeat: Int) {
    OFF("Off", longArrayOf(), -1),
    SHORT("Short", longArrayOf(0, 300, 150, 300), -1),
    LONG("Long", longArrayOf(0, 600, 250, 600, 250, 600), -1),
    CONTINUOUS("Continuous", longArrayOf(0, 500, 400), 1);

    companion object {
        fun parse(s: String) = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: LONG
    }
}

/** A notification the listener has seen, kept so rules can be built from real examples. */
data class Seen(
    val pkg: String,
    val appLabel: String,
    val channelId: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** Labels of the notification's buttons. */
    val actions: List<String> = emptyList(),
) {
    val matchText get() = "$title\n$text"

    fun toJson(): JSONObject = JSONObject()
        .put("pkg", pkg).put("appLabel", appLabel).put("channelId", channelId)
        .put("title", title).put("text", text).put("postedAt", postedAt)
        .put("actions", JSONArray(actions))

    companion object {
        fun fromJson(o: JSONObject) = Seen(
            o.getString("pkg"), o.optString("appLabel"), o.optString("channelId"),
            o.optString("title"), o.optString("text"), o.optLong("postedAt"),
            o.optJSONArray("actions")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
        )
    }
}

/**
 * Rules and recent notifications live in small JSON files that are re-read on every use,
 * so the UI, the listener service and `adb shell run-as` edits never go stale.
 */
object Store {
    private const val RULES = "rules.json"
    private const val RECENT = "recent.json"
    private const val MAX_RECENT = 50
    private const val SILENT_UNTIL = "silent_until"
    private const val CAPTURE_UNTIL = "capture_until"
    const val SILENT_TEST_MS = 10 * 60_000L
    const val CAPTURE_MS = 10 * 60_000L

    private var cachedRules: List<Rule> = emptyList()
    private var rulesStamp = Long.MIN_VALUE

    /** Silent test mode: alerts run at zero volume with no vibration. Expires on its own. */
    fun silentTestActive(ctx: Context): Boolean = silentUntil(ctx) > System.currentTimeMillis()

    fun silentUntil(ctx: Context): Long =
        File(ctx.filesDir, SILENT_UNTIL).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L

    fun setSilentTest(ctx: Context, on: Boolean) {
        val f = File(ctx.filesDir, SILENT_UNTIL)
        if (on) f.writeText((System.currentTimeMillis() + SILENT_TEST_MS).toString()) else f.delete()
    }

    /**
     * Capture mode: record notifications from every app, so rules can be built from them.
     * Outside capture mode only apps with an enabled rule are looked at. When capture ends
     * (switched off or expired), other apps' entries are dropped from the recent list.
     */
    fun captureActive(ctx: Context): Boolean {
        val f = File(ctx.filesDir, CAPTURE_UNTIL)
        if (!f.exists()) return false // the common case: a single stat, no read
        if (captureUntil(ctx) > System.currentTimeMillis()) return true
        f.delete()
        pruneRecent(ctx)
        return false
    }

    fun captureUntil(ctx: Context): Long =
        File(ctx.filesDir, CAPTURE_UNTIL).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L

    fun setCapture(ctx: Context, on: Boolean) {
        val f = File(ctx.filesDir, CAPTURE_UNTIL)
        if (on) {
            f.writeText((System.currentTimeMillis() + CAPTURE_MS).toString())
        } else {
            f.delete()
            pruneRecent(ctx)
        }
    }

    /** Whether to look at a notification from [pkg] at all. */
    fun wanted(ctx: Context, pkg: String): Boolean =
        rules(ctx).any { it.enabled && it.pkg == pkg } || captureActive(ctx)

    /** Rules, cached in memory and re-read only when the file changes (so run-as edits still apply). */
    @Synchronized
    fun rules(ctx: Context): List<Rule> {
        val f = File(ctx.filesDir, RULES)
        val stamp = if (f.exists()) f.lastModified() * 31 + f.length() else 0L
        if (stamp != rulesStamp) {
            cachedRules = read(ctx, RULES).map(Rule::fromJson)
            rulesStamp = stamp
        }
        return cachedRules
    }

    @Synchronized
    fun saveRules(ctx: Context, rules: List<Rule>) {
        write(ctx, RULES, rules.map { it.toJson() })
        rulesStamp = Long.MIN_VALUE
        if (!captureActive(ctx)) pruneRecent(ctx)
    }

    fun recent(ctx: Context): List<Seen> = read(ctx, RECENT).map(Seen::fromJson)

    /** Drop recent entries from apps without an enabled rule. */
    @Synchronized
    fun pruneRecent(ctx: Context) {
        val keep = rules(ctx).filter { it.enabled }.map { it.pkg }.toSet()
        val old = recent(ctx)
        val kept = old.filter { it.pkg in keep }
        if (kept.size != old.size) write(ctx, RECENT, kept.map { it.toJson() })
    }

    @Synchronized
    fun addRecent(ctx: Context, n: Seen) {
        val old = recent(ctx)
        val same = { s: Seen -> s.pkg == n.pkg && s.title == n.title && s.text == n.text && s.actions == n.actions }
        if (old.firstOrNull()?.let(same) == true) return // repeated update, e.g. media progress
        write(ctx, RECENT, (listOf(n) + old.filterNot(same)).take(MAX_RECENT).map { it.toJson() })
    }

    private fun read(ctx: Context, name: String): List<JSONObject> {
        val f = File(ctx.filesDir, name)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            List(arr.length()) { arr.getJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun write(ctx: Context, name: String, items: List<JSONObject>) {
        val tmp = File(ctx.filesDir, "$name.tmp")
        tmp.writeText(JSONArray(items).toString(2))
        tmp.renameTo(File(ctx.filesDir, name))
    }
}
