package com.jarvis.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.DownloadManager
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Action(val type: String, val args: JSONObject) {
    val sensitive: Boolean
        get() = type in setOf("CALL", "SMS", "DELETE_FILE", "PAYMENT") ||
            (type == "CLICK_TEXT" && RISKY.containsMatchIn(args.optString("text")))

    override fun toString() = "$type ${args}"

    companion object {
        private val RISKY = Regex(
            "pay|send|delete|buy|order|transfer|purchase|confirm|remove|भेज|भुगतान|हटा|खरीद|डिलीट",
            RegexOption.IGNORE_CASE
        )
    }
}

data class Result(val text: String, val feedback: Boolean = false)

object Redactor {
    private val num = Regex("\\b\\d{4,8}\\b")
    fun clean(s: String) = num.replace(s, "[number]")
}

class ActionExecutor(private val ctx: Context) {

    private val ok = Result("ok")
    private val noAcc = Result("Accessibility service is OFF. Ask the user to enable it in Settings.", true)

    suspend fun execute(a: Action): Result = try {
        dispatch(a)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result("${a.type} failed: ${e.message}", true)
    }

    private fun launch(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun granted(p: String) = SystemInfo.has(ctx, p)
    private fun acc() = JarvisAccessibilityService.instance

    private suspend fun dispatch(a: Action): Result {
        val g = a.args
        return when (a.type) {
            "OPEN_APP" -> openApp(g.optString("name"))
            "CLOSE_APP" -> acc()?.let { it.global("HOME"); ok } ?: noAcc
            "GLOBAL" -> acc()?.let {
                val done = it.global(g.optString("action"))
                Result(if (done) "ok" else "Global action failed", !done)
            } ?: noAcc
            "OPEN_SETTINGS" -> { launch(Intent(settingsAction(g.optString("page")))); ok }
            "FLASHLIGHT" -> torch(g.optBoolean("on", true))
            "VOLUME" -> volume(g)
            "BRIGHTNESS" -> brightness(g.optInt("level", 50))
            "ALARM" -> alarm(g)
            "TIMER" -> {
                launch(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, g.optInt("seconds", 60))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, g.optString("label", "Timer"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                ok
            }
            "CALL" -> call(g)
            "SMS" -> sms(g)
            "FIND_CONTACT" -> {
                val l = contacts(g.optString("name"))
                Result(
                    if (l.isEmpty()) "No contact found (or contacts permission missing)"
                    else l.take(5).joinToString("; ") { "${it.first}: ${it.second}" }, true
                )
            }
            "NOTIFICATIONS" -> JarvisNotificationListener.instance?.let {
                Result(Redactor.clean(it.recent().ifEmpty { listOf("No notifications") }.joinToString("\n")), true)
            } ?: Result("Notification access is OFF. Ask the user to enable it in Settings.", true)
            "BATTERY" -> {
                val (p, c) = SystemInfo.battery(ctx)
                Result("Battery $p%" + if (c) ", charging" else "", true)
            }
            "STORAGE" -> {
                val (f, t) = SystemInfo.storage(ctx)
                Result("Storage: ${SystemInfo.gb(f)} free of ${SystemInfo.gb(t)}", true)
            }
            "WEATHER" -> weather()
            "APP_USAGE" -> usage()
            "TODAY_SCHEDULE" -> schedule()
            "CALENDAR_ADD" -> calendarAdd(g)
            "MEDIA" -> media(g.optString("cmd"))
            "OPEN_URL" -> openUrl(g.optString("url"))
            "SEARCH" -> openUrl("https://www.google.com/search?q=" + Uri.encode(g.optString("query")))
            "OPEN_FILES" -> { launch(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); ok }
            "READ_SCREEN" -> acc()?.let {
                Result(Redactor.clean(it.readScreen()).ifBlank { "(screen empty or unreadable)" }, true)
            } ?: noAcc
            "CLICK_TEXT" -> acc()?.let {
                val r = it.clickText(g.optString("text"))
                Result(if (r) "clicked" else "No clickable element with that text", !r)
            } ?: noAcc
            "TYPE_TEXT" -> acc()?.let {
                val r = it.typeText(g.optString("text"))
                Result(if (r) "typed" else "No editable (non-password) field available", !r)
            } ?: noAcc
            "SCROLL" -> acc()?.let {
                val r = it.scroll(g.optString("direction", "forward") != "backward")
                Result(if (r) "ok" else "Nothing scrollable", !r)
            } ?: noAcc
            "SWIPE" -> acc()?.let {
                val r = it.swipe(g.optString("direction"))
                Result(if (r) "ok" else "Swipe failed", !r)
            } ?: noAcc
            else -> Result("Unsupported action ${a.type}", true)
        }
    }

    private fun openApp(name: String): Result {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return Result("No app name given", true)
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        fun label(r: ResolveInfo) = r.loadLabel(pm).toString().lowercase()
        val m = apps.firstOrNull { label(it) == q }
            ?: apps.firstOrNull { label(it).contains(q) }
            ?: apps.firstOrNull { label(it).length > 2 && q.contains(label(it)) }
            ?: return Result("App '$name' not found", true)
        launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(m.activityInfo.packageName, m.activityInfo.name))
        return ok
    }

    private fun settingsAction(page: String): String = when (page) {
        "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "display" -> Settings.ACTION_DISPLAY_SETTINGS
        "sound" -> Settings.ACTION_SOUND_SETTINGS
        "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
        "apps" -> Settings.ACTION_APPLICATION_SETTINGS
        "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
        else -> Settings.ACTION_SETTINGS
    }

    private fun torch(on: Boolean): Result {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Result("This phone has no flashlight", true)
        cm.setTorchMode(id, on)
        return ok
    }

    private fun volume(g: JSONObject): Result {
        val am = ctx.getSystemService(AudioManager::class.java)
        val stream = when (g.optString("stream", "media")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        if (g.has("level")) {
            val max = am.getStreamMaxVolume(stream)
            am.setStreamVolume(stream, (g.getInt("level") * max / 100).coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
        } else {
            val dir = if (g.optString("direction") == "down") AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
            am.adjustStreamVolume(stream, dir, AudioManager.FLAG_SHOW_UI)
        }
        return ok
    }

    private fun brightness(level: Int): Result {
        if (!Settings.System.canWrite(ctx)) {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return Result("Needs 'Modify system settings' permission; I opened that page for the user.", true)
        }
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (level * 255 / 100).coerceIn(1, 255))
        return ok
    }

    private fun alarm(g: JSONObject): Result {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, g.optInt("hour"))
            .putExtra(AlarmClock.EXTRA_MINUTES, g.optInt("minute"))
            .putExtra(AlarmClock.EXTRA_MESSAGE, g.optString("label", "Jarvis"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        g.optJSONArray("days")?.let { d ->
            if (d.length() > 0) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList((0 until d.length()).map { d.getInt(it) }))
        }
        launch(i)
        return ok
    }

    private fun contacts(name: String): List<Pair<String, String>> {
        if (name.isBlank() || !granted(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )?.use { c -> while (c.moveToNext()) out += c.getString(0) to c.getString(1) }
        return out
    }

    private fun numberFrom(g: JSONObject): String? {
        val n = g.optString("number")
        return if (n.isNotBlank()) n else contacts(g.optString("name")).firstOrNull()?.second
    }

    private fun call(g: JSONObject): Result {
        val number = numberFrom(g) ?: return Result("Could not find that contact or number", true)
        val uri = Uri.parse("tel:" + Uri.encode(number))
        launch(if (granted(Manifest.permission.CALL_PHONE)) Intent(Intent.ACTION_CALL, uri) else Intent(Intent.ACTION_DIAL, uri))
        return ok
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    private fun sms(g: JSONObject): Result {
        val number = numberFrom(g) ?: return Result("Could not find that contact or number", true)
        val text = g.optString("text")
        if (granted(Manifest.permission.SEND_SMS)) {
            val sm = smsManager()
            sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
            return ok
        }
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text))
        return Result("SMS permission missing: opened the messages app with a draft; the user must press send.", true)
    }

    @SuppressLint("MissingPermission")
    private suspend fun weather(): Result = withContext(Dispatchers.IO) {
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return@withContext Result("Location permission not granted", true)
        val lm = ctx.getSystemService(LocationManager::class.java)
        val loc = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            ?: return@withContext Result("No location fix. Turn on location / open Maps once.", true)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=auto"
        val c = URL(url).openConnection().apply { connectTimeout = 10000; readTimeout = 10000 }
        Result("Open-Meteo current weather (WMO weather_code): " + c.getInputStream().bufferedReader().readText(), true)
    }

    private fun usage(): Result {
        val aom = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) {
            launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return Result("Usage access not granted; opened the settings page for the user.", true)
        }
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val stats = ctx.getSystemService(UsageStatsManager::class.java)
            .queryAndAggregateUsageStats(start, System.currentTimeMillis())
        val pm = ctx.packageManager
        val top = stats.values.filter { it.totalTimeInForeground > 60_000 }
            .sortedByDescending { it.totalTimeInForeground }.take(5)
            .joinToString("\n") {
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0)) } catch (e: Exception) { it.packageName }
                "$label: ${it.totalTimeInForeground / 60000} min"
            }
        return Result("Screen time today:\n" + top.ifBlank { "No data" }, true)
    }

    @SuppressLint("MissingPermission")
    private fun schedule(): Result {
        if (!granted(Manifest.permission.READ_CALENDAR)) return Result("Calendar permission not granted", true)
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, start); ContentUris.appendId(b, start + 86_400_000L)
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val out = mutableListOf<String>()
        ctx.contentResolver.query(
            b.build(), arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c -> while (c.moveToNext()) out += "${fmt.format(Date(c.getLong(1)))} ${c.getString(0)}" }
        return Result(if (out.isEmpty()) "No events today" else out.joinToString("\n"), true)
    }

    private fun calendarAdd(g: JSONObject): Result {
        val begin = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(g.optString("start"))?.time
            ?: return Result("Bad date format", true)
        launch(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, g.optString("title"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + g.optInt("duration_min", 60) * 60_000L))
        return Result("Opened calendar with the event; the user must tap Save.", false)
    }

    private fun media(cmd: String): Result {
        val code = when (cmd) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ok
    }

    private fun openUrl(url: String): Result {
        val u = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        return ok
    }
}
