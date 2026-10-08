package com.snoozy.app

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// Snoozes that start on their own, e.g. every night at 11 PM for 8 hours.
// While one is running, Reels stay blocked and locked, the same as a snooze
// started by hand. Nothing is registered with the system: whether a schedule
// is running is worked out from the clock whenever it's asked, so it keeps
// working after a reboot and needs no alarm permission.
object Schedules {

    const val MAX_SCHEDULES = 5
    const val EVERY_DAY = 0b1111111
    private const val WEEKDAYS = 0b0111110
    private const val WEEKENDS = 0b1000001

    private const val PREFS = "schedules"
    private const val KEY = "list"
    private const val MINUTE = 60_000L

    // start: minutes after midnight. days: one bit per day the snooze starts
    // on, bit (Calendar.DAY_OF_WEEK - 1), so Sunday is bit 0.
    data class Schedule(
        val id: Long,
        val start: Int,
        val minutes: Int,
        val days: Int,
        val enabled: Boolean,
    ) {
        val end get() = (start + minutes) % (24 * 60)
    }

    // One run of a schedule, in epoch ms.
    class Window(val start: Long, val end: Long)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // The service asks many times a second, so only re-parse after a change.
    private var cachedRaw: String? = null
    private var cached: List<Schedule> = emptyList()

    fun get(context: Context): List<Schedule> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        if (raw != cachedRaw) {
            val array = JSONArray(raw)
            cached = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Schedule(o.getLong("id"), o.getInt("start"), o.getInt("minutes"), o.getInt("days"), o.getBoolean("enabled"))
            }
            cachedRaw = raw
        }
        return cached
    }

    private fun save(context: Context, list: List<Schedule>) {
        val array = JSONArray()
        for (s in list.sortedBy { it.start }) {
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("start", s.start)
                    .put("minutes", s.minutes)
                    .put("days", s.days)
                    .put("enabled", s.enabled)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    // Why a schedule can't be added, or null if it can.
    fun problem(context: Context, minutes: Int, days: Int): String? = when {
        minutes <= 0 -> "Pick how long to snooze for."
        minutes > SnoozeOptions.MAX_MINUTES -> "The longest snooze is 24 hours."
        days == 0 -> "Pick at least one day."
        // Back-to-back runs would never end, so it could never be removed.
        minutes >= SnoozeOptions.MAX_MINUTES && days == EVERY_DAY ->
            "24 hours every day would never end. Pick a shorter time or fewer days."
        get(context).size >= MAX_SCHEDULES -> "You can have up to $MAX_SCHEDULES schedules. Remove one first."
        else -> null
    }

    // Returns why the schedule can't be added, or null once it's added.
    fun add(context: Context, start: Int, minutes: Int, days: Int): String? {
        problem(context, minutes, days)?.let { return it }
        save(context, get(context) + Schedule(System.currentTimeMillis(), start, minutes, days, enabled = true))
        return null
    }

    // Like any snooze, a running schedule can't be stopped early: turning it
    // off or removing it returns false and changes nothing.
    fun setEnabled(context: Context, id: Long, enabled: Boolean): Boolean {
        val s = get(context).find { it.id == id } ?: return false
        if (!enabled && isRunning(s)) return false
        save(context, get(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
        return true
    }

    fun remove(context: Context, id: Long): Boolean {
        val s = get(context).find { it.id == id } ?: return false
        if (isRunning(s)) return false
        save(context, get(context) - s)
        return true
    }

    fun isRunning(s: Schedule): Boolean = s.enabled && window(s) != null

    // The run of `s` that covers `now`, whether or not it's switched on. A
    // run lasts at most 24 hours, so only today's and yesterday's starts can
    // still be going.
    fun window(s: Schedule, now: Long = System.currentTimeMillis()): Window? {
        for (daysAgo in 0..1) {
            val start = startOn(s, now, -daysAgo) ?: continue
            val end = start + s.minutes * MINUTE
            if (now in start until end) return Window(start, end)
        }
        return null
    }

    // The running schedule that ends last, if any.
    fun active(context: Context, now: Long = System.currentTimeMillis()): Window? =
        get(context).filter { it.enabled }.mapNotNull { window(it, now) }.maxByOrNull { it.end }

    // When the next switched-on schedule starts, if any.
    fun nextStart(context: Context, now: Long = System.currentTimeMillis()): Long? =
        get(context).filter { it.enabled }
            .flatMap { s -> (0..7).mapNotNull { startOn(s, now, it) } }
            .filter { it > now }
            .minOrNull()

    // When `s` starts on the day `dayOffset` days from `now`'s, or null if it
    // doesn't start that day. Calendar keeps this right across DST changes.
    private fun startOn(s: Schedule, now: Long, dayOffset: Int): Long? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, s.start / 60)
            set(Calendar.MINUTE, s.start % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return if ((s.days and dayBit(cal.get(Calendar.DAY_OF_WEEK))) != 0) cal.timeInMillis else null
    }

    fun dayBit(dayOfWeek: Int) = 1 shl (dayOfWeek - 1)

    // Calendar.DAY_OF_WEEK values, starting from the locale's first day.
    fun weekOrder(): List<Int> {
        val first = Calendar.getInstance().firstDayOfWeek
        return (0 until 7).map { (first - 1 + it) % 7 + 1 }
    }

    // "Every day", "Weekdays", "Weekends" or "Mon, Wed, Fri".
    fun daysLabel(days: Int): String = when (days) {
        EVERY_DAY -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKENDS -> "Weekends"
        else -> {
            val names = DateFormatSymbols.getInstance().shortWeekdays
            weekOrder().filter { (days and dayBit(it)) != 0 }.joinToString(", ") { names[it] }
        }
    }

    // 1380 -> "11:00 PM" (or "23:00", following the phone's setting).
    fun timeLabel(context: Context, minuteOfDay: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
        }
        return DateFormat.getTimeFormat(context).format(cal.time)
    }

    // "11:00 PM", "tomorrow at 11:00 PM" or "Tue at 11:00 PM".
    fun whenLabel(context: Context, ms: Long): String {
        val time = DateFormat.getTimeFormat(context).format(ms)
        return when {
            DateUtils.isToday(ms) -> time
            DateUtils.isToday(ms - DateUtils.DAY_IN_MILLIS) -> "tomorrow at $time"
            else -> "${SimpleDateFormat("EEE", Locale.getDefault()).format(ms)} at $time"
        }
    }
}
