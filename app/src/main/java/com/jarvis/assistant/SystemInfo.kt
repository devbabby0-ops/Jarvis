package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SystemInfo {
    fun has(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun battery(ctx: Context): Pair<Int, Boolean> {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return pct to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    }

    fun storage(ctx: Context): Pair<Long, Long> {
        val s = StatFs(Environment.getDataDirectory().path)
        return s.availableBytes to s.totalBytes
    }

    fun gb(b: Long) = "%.1f GB".format(b / 1e9)

    /** (label, value, healthy) triples for the dashboard. */
    fun snapshot(ctx: Context): List<Triple<String, String, Boolean>> {
        val (pct, charging) = battery(ctx)
        val (free, _) = storage(ctx)
        val notif = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        return listOf(
            Triple("TIME", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), true),
            Triple("BATTERY", "$pct%" + if (charging) " ⚡" else "", pct > 15),
            Triple("FREE", gb(free), true),
            Triple("MIC", if (has(ctx, Manifest.permission.RECORD_AUDIO)) "OK" else "OFF", has(ctx, Manifest.permission.RECORD_AUDIO)),
            Triple("ACCESS", if (JarvisAccessibilityService.instance != null) "ON" else "OFF", JarvisAccessibilityService.instance != null),
            Triple("NOTIFS", if (notif) "ON" else "OFF", notif)
        )
    }
}
