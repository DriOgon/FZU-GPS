package com.driogon.fzucheckin.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object TimeRules {
    val china: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun minute(value: String): Int? = try {
        // School time fields can be space-padded; Python int() accepted this padding.
        val parts = value.trim().split(':')
        val t = LocalTime.of(parts[0].trim().toInt(), parts[1].trim().toInt())
        t.hour * 60 + t.minute
    } catch (_: Exception) { null }

    fun inRange(now: ZonedDateTime, start: String, end: String): Boolean {
        val s = minute(start) ?: return false
        val e = minute(end) ?: return false
        val local = now.withZoneSameInstant(china)
        val n = local.hour * 60 + local.minute
        return if (s <= e) n in s..e else n >= s || n <= e
    }

    fun isLate(now: ZonedDateTime, late: String, start: String, end: String): Int {
        val l = minute(late) ?: return 0
        val s = minute(start) ?: return 0
        val e = minute(end) ?: return 0
        val local = now.withZoneSameInstant(china)
        var n = local.hour * 60 + local.minute
        var threshold = l
        if (s > e) {
            if (n < s) n += 1440
            if (threshold < s) threshold += 1440
        }
        return if (n > threshold) 1 else 0
    }

    fun fromTimestamp(value: String, fallback: () -> ZonedDateTime): Pair<ZonedDateTime, Boolean> = try {
        val n = value.toDouble()
        require(n.isFinite())
        val millis = if (n > 10_000_000_000) n.toLong() else (n * 1000).toLong()
        Instant.ofEpochMilli(millis).atZone(china) to true
    } catch (_: Exception) { fallback().withZoneSameInstant(china) to false }

    /** End is exclusive, including the school's entire final allowed minute. */
    fun window(now: ZonedDateTime, start: String, end: String): Pair<ZonedDateTime, ZonedDateTime>? {
        val s = minute(start) ?: return null
        val e = minute(end) ?: return null
        val local = now.withZoneSameInstant(china)
        var date = local.toLocalDate()
        if (s > e && local.hour * 60 + local.minute <= e) date = date.minusDays(1)
        val first = date.atStartOfDay(china).plusMinutes(s.toLong())
        val last = date.plusDays(if (s > e) 1 else 0).atStartOfDay(china).plusMinutes(e.toLong() + 1)
        return first to last
    }
}
