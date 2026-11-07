package com.driogon.fzucheckin.automation

import com.driogon.fzucheckin.core.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime

data class AutomationSettings(
    val enabled: Boolean = false,
    val minute: Int = 21 * 60 + 5,
    val version: Long = 0,
    val generation: Long = 0,
    val accountId: String = "",
    val firstDue: Long = 0,
    val paused: String = "",
    val lastMessage: String = "尚未执行",
) {
    val timeLabel: String get() = "%02d:%02d".format(minute / 60, minute % 60)
    val active: Boolean get() = enabled && paused.isEmpty() && accountId.isNotEmpty()
}

data class AutoTask(val version: Long, val generation: Long, val due: Long) {
    val id: String get() = "$version:$generation:$due"
}

object AutoTimes {
    fun next(now: ZonedDateTime, minute: Int): ZonedDateTime {
        val local = now.withZoneSameInstant(TimeRules.china)
        val today = local.toLocalDate().atStartOfDay(TimeRules.china).plusMinutes(minute.toLong())
        return if (today.isAfter(local)) today else today.plusDays(1)
    }
    fun latest(now: ZonedDateTime, minute: Int): ZonedDateTime = next(now, minute).minusDays(1)
    fun millis(value: ZonedDateTime): Long = value.toInstant().toEpochMilli()
    fun at(value: Long): ZonedDateTime = Instant.ofEpochMilli(value).atZone(TimeRules.china)
    fun current(settings: AutomationSettings, task: AutoTask): Boolean = settings.active &&
        settings.version == task.version && settings.generation == task.generation && task.due >= settings.firstDue
    fun unexpired(task: AutoTask, now: ZonedDateTime): Boolean =
        !now.isBefore(at(task.due)) && now.isBefore(at(task.due).plusDays(1))
}

fun accountId(username: String): String = MessageDigest.getInstance("SHA-256")
    .digest(username.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun submissionKey(account: String, snapshot: Snapshot): String? {
    if (snapshot.planId.isBlank()) return null
    val window = TimeRules.window(snapshot.serverNow, snapshot.start, snapshot.end) ?: return null
    return "$account|${snapshot.planId}|${AutoTimes.millis(window.first)}"
}

fun automaticBlock(snapshot: Snapshot, task: AutoTask): String? {
    if (!snapshot.serverTimeAvailable) return "服务器时间暂不可用"
    if (!AutoTimes.unexpired(task, snapshot.serverNow)) return "本次任务已过期"
    return when (snapshot.status) {
        CheckinStatus.SUCCESS -> "已签到，已跳过"
        CheckinStatus.PROCESSING, CheckinStatus.UNKNOWN_RESULT -> "签到结果待确认，请打开 App 核实"
        CheckinStatus.NO_PLAN -> "学校暂未分配签到计划"
        CheckinStatus.NEED_NOT -> "学校安排当前无需签到"
        CheckinStatus.OUT_OF_TIME -> "当前不在学校允许的签到时间内"
        CheckinStatus.UNKNOWN_WAY -> "暂不支持学校返回的签到方式，请手动处理"
        else -> if (snapshot.way != 1) "学校指定校外签到，请打开 App 填写地址手动处理"
        else {
            val window = TimeRules.window(snapshot.serverNow, snapshot.start, snapshot.end)
            val due = AutoTimes.at(task.due)
            if (window == null || due.isBefore(window.first) || !due.isBefore(window.second))
                "设定时间不在本次学校签到时段内，请修改时间"
            else null
        }
    }
}
