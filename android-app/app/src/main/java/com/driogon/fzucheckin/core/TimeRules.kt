package com.driogon.fzucheckin.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object TimeRules {
    val china: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun minute(value: String): Int? = try {
        val parts = value.split(':')
        val t = LocalTime.of(parts[0].toInt(), parts[1].toInt())
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
}
