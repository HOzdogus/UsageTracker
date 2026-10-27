package com.screentime.tracker

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import java.time.LocalDate
import java.time.ZoneId

data class AppUsage(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val foregroundMs: Long,
    val opens: Int,
    /** Time on screen in each hour of the day, index 0 = midnight to 1am. */
    val hourlyMs: List<Long>,
    /** How many times it was opened in each hour of the day. */
    val hourlyOpens: List<Int>,
)

data class DayUsage(
    val date: LocalDate,
    val totalMs: Long,
    val apps: List<AppUsage>,
)

class UsageRepository(private val context: Context) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val pm = context.packageManager

    /** True once the user has switched on "Usage access" for this app in Settings. */
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Works out foreground time per app for one calendar day by replaying the raw
     * resume/pause events. This is more accurate than queryUsageStats(), whose daily
     * buckets don't line up with midnight and often double-count.
     */
    fun loadDay(date: LocalDate): DayUsage {
        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val windowEnd = minOf(dayEnd, now)

        // Look back a bit so we know which app was already open at midnight.
        val lookBack = 2 * 60 * 60 * 1000L
        val events = usm.queryEvents(dayStart - lookBack, windowEnd)

        val resumedActivities = HashMap<String, MutableSet<String>>() // pkg -> open activity classes
        val foregroundSince = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        val seen = HashSet<String>()
        val hourlyTime = HashMap<String, LongArray>()
        val hourlyOpenCounts = HashMap<String, IntArray>()

        // The start of every hour in this day. Stepping by real hours means the
        // clock-change days (23 or 25 hours long) still land in the right slots.
        val hourStarts = ArrayList<Long>()
        val hourLabels = ArrayList<Int>()
        val nextMidnight = date.plusDays(1).atStartOfDay(zone)
        var cursor = date.atStartOfDay(zone)
        while (cursor.isBefore(nextMidnight)) {
            hourStarts.add(cursor.toInstant().toEpochMilli())
            hourLabels.add(cursor.hour)
            cursor = cursor.plusHours(1)
        }

        fun hourOf(ts: Long): Int {
            var hour = 0
            for (i in hourStarts.indices) {
                if (ts >= hourStarts[i]) hour = hourLabels[i]
            }
            return hour
        }

        fun addTime(pkg: String, from: Long, to: Long) {
            val start = maxOf(from, dayStart)
            val end = minOf(to, windowEnd)
            if (end <= start) return
            totals[pkg] = (totals[pkg] ?: 0L) + (end - start)

            // Split the stretch across the hours it covers.
            val perHour = hourlyTime.getOrPut(pkg) { LongArray(24) }
            for (i in hourStarts.indices) {
                val slotStart = hourStarts[i]
                val slotEnd = if (i + 1 < hourStarts.size) hourStarts[i + 1] else dayEnd
                val overlapStart = maxOf(start, slotStart)
                val overlapEnd = minOf(end, slotEnd)
                if (overlapEnd > overlapStart) {
                    perHour[hourLabels[i]] += overlapEnd - overlapStart
                }
            }
        }

        fun closeAll(at: Long) {
            for ((pkg, since) in foregroundSince) addTime(pkg, since, at)
            foregroundSince.clear()
            resumedActivities.clear()
        }

        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val ts = e.timeStamp
            val cls = e.className ?: ""

            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    seen.add(pkg)
                    val set = resumedActivities.getOrPut(pkg) { mutableSetOf() }
                    if (set.isEmpty()) {
                        foregroundSince[pkg] = ts
                        if (ts >= dayStart) {
                            opens[pkg] = (opens[pkg] ?: 0) + 1
                            hourlyOpenCounts.getOrPut(pkg) { IntArray(24) }[hourOf(ts)] += 1
                        }
                    }
                    set.add(cls)
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val set = resumedActivities[pkg]
                    if (set == null || set.isEmpty()) {
                        // First thing we ever see for this app is a pause: it was already
                        // on screen when our window began, so count from the window start.
                        if (pkg !in seen && e.eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
                            addTime(pkg, dayStart - lookBack, ts)
                        }
                        seen.add(pkg)
                        continue
                    }
                    set.remove(cls)
                    if (set.isEmpty()) {
                        foregroundSince.remove(pkg)?.let { addTime(pkg, it, ts) }
                    }
                }
                // Screen off / shutdown ends whatever was on screen.
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.DEVICE_SHUTDOWN -> closeAll(ts)
            }
        }
        // Anything still open counts up to now (or midnight for past days).
        closeAll(windowEnd)

        val self = context.packageName
        val apps = totals
            .filter { (pkg, ms) -> ms >= 1000 && pkg != self && isUserFacing(pkg) }
            .map { (pkg, ms) ->
                AppUsage(
                    packageName = pkg,
                    label = labelFor(pkg),
                    icon = iconFor(pkg),
                    foregroundMs = ms,
                    opens = opens[pkg] ?: 0,
                    hourlyMs = (hourlyTime[pkg] ?: LongArray(24)).toList(),
                    hourlyOpens = (hourlyOpenCounts[pkg] ?: IntArray(24)).toList(),
                )
            }
            .sortedByDescending { it.foregroundMs }

        return DayUsage(date, apps.sumOf { it.foregroundMs }, apps)
    }

    // Skips the home screen and background system bits so the list is apps you actually use.
    private val launcherPackages: Set<String> by lazy {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }

    private fun isUserFacing(pkg: String): Boolean =
        pkg !in launcherPackages && pm.getLaunchIntentForPackage(pkg) != null

    private fun labelFor(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun iconFor(pkg: String): Bitmap? = try {
        pm.getApplicationIcon(pkg).toBitmap(96, 96)
    } catch (_: Exception) {
        null
    }
}

fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "<1m"
    }
}
