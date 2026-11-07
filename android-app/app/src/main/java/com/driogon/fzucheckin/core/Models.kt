package com.driogon.fzucheckin.core

import com.google.gson.JsonObject
import java.time.ZonedDateTime

data class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials(<redacted>)"
}

enum class ErrorKind { NETWORK, AUTH, CAPTCHA, PROTOCOL, VALIDATION }
class CheckinException(val kind: ErrorKind, message: String) : Exception(message)

enum class CheckinStatus {
    READY, SUCCESS, PROCESSING, NO_PLAN, NEED_NOT, OUT_OF_TIME, NEEDS_ADDRESS, UNKNOWN_WAY, UNKNOWN_RESULT,
}

data class Campus(val id: String, val title: String)
data class Snapshot(
    val name: String,
    val college: String,
    val planId: String,
    val way: Int?,
    val start: String,
    val end: String,
    val late: String,
    val serverNow: ZonedDateTime,
    val serverTimeAvailable: Boolean,
    val status: CheckinStatus,
    val campus: Campus,
    val checkInTime: String = "",
    val actualLocation: String = "",
    // Raw server range data never leaves the repository layer or gets logged.
    val raw: JsonObject = JsonObject(),
) {
    val wayLabel: String get() = when (way) { 1 -> "校内"; 2 -> "校外"; 3 -> "无需签到"; else -> "暂不支持" }
}

data class SubmitOutcome(val snapshot: Snapshot, val notice: String? = null)

fun interface SubmissionGuard {
    fun beforeDispatch(snapshot: Snapshot)
}

interface CheckinRepository {
    fun login(credentials: Credentials)
    fun refresh(): Snapshot
    fun submit(address: String, guard: SubmissionGuard = SubmissionGuard {}, onConfirming: () -> Unit = {}): SubmitOutcome
    fun clearSession()
}

fun JsonObject.text(name: String): String = get(name)?.takeUnless { it.isJsonNull || !it.isJsonPrimitive }?.asString.orEmpty()
fun JsonObject.obj(name: String): JsonObject = get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
