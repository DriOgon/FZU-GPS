package com.driogon.fzucheckin.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

data class ServiceConfig(
    val baseUrl: String = "https://yzsxg.fzu.edu.cn",
    val authorizeUrl: String = "https://yzsxg.fzu.edu.cn/livecloud/project/fzu/attn/oauth2/authorize.action",
    val ssoHosts: Set<String> = setOf("sso.fzu.edu.cn"),
    val latitude: Double = 26.05,
    val longitude: Double = 119.19,
    val caIsNo: String = "0",
    val allowHttpForTests: Boolean = false,
)

private class MemoryCookies : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies += cookie
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
    @Synchronized fun clear() = cookies.clear()
}

class FzuRepository(
    private val config: ServiceConfig = ServiceConfig(),
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(TimeRules.china) },
) : CheckinRepository {
    private val base = config.baseUrl.toHttpUrl()
    private val cookies = MemoryCookies()
    private val client = OkHttpClient.Builder().cookieJar(cookies)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()
    private var token: String? = null
    private var pendingSubmission = false
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    override fun clearSession() {
        token = null
        pendingSubmission = false
        cookies.clear()
        client.connectionPool.evictAll()
    }

    override fun login(credentials: Credentials) {
        require(credentials.username.isNotBlank() && credentials.password.isNotEmpty()) { "请输入账号和密码" }
        // Retain uncertainty across a token refresh. A new login must never replay a submission.
        token = null
        cookies.clear()
        var page = request(Request.Builder().url(config.authorizeUrl))
        for (index in 0..20) {
            findToken(page)?.let { token = it; return }
            val next = page.location
            if (next != null && page.code in 300..399) {
                if (index == 20) fail(ErrorKind.PROTOCOL, "登录跳转次数过多")
                page = request(Request.Builder().url(next))
            } else break
        }
        if (page.url.host !in config.ssoHosts) fail(ErrorKind.AUTH, "未进入学校统一认证页面，请稍后重试")
        val form = SsoParser.parse(page.body)
        var loginUrl = if (form.action.isBlank()) page.url else page.url.resolve(form.action)
            ?: fail(ErrorKind.PROTOCOL, "学校登录表单地址无效")
        if (loginUrl.host !in config.ssoHosts) fail(ErrorKind.PROTOCOL, "学校登录表单跳转到了非认证站点")
        if (page.url.encodedPath == loginUrl.encodedPath && page.url.encodedQuery != null && loginUrl.encodedQuery == null) {
            loginUrl = loginUrl.newBuilder().encodedQuery(page.url.encodedQuery).build()
        }
        val fields = LinkedHashMap(form.fields)
        fields["username"] = credentials.username.trim()
        fields["password"] = ProtocolCrypto.encryptPassword(credentials.password, form.crypto)
        fields["type"] = form.loginType
        fields["_eventId"] = "submit"
        fields["execution"] = form.execution
        fields["croypto"] = form.crypto
        fields["geolocation"] = ""
        for (key in listOf("captcha_code", "captcha_payload")) if (key in fields) fields[key] = ""
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        page = request(Request.Builder().url(loginUrl).post(body)
            .header("Referer", page.url.toString())
            .header("Origin", "${loginUrl.scheme}://${loginUrl.host}${if (loginUrl.port == 443) "" else ":${loginUrl.port}"}"))
        repeat(21) { index ->
            findToken(page)?.let { token = it; return }
            val next = page.location
            if (next != null && page.code in 300..399) {
                if (index == 20) fail(ErrorKind.PROTOCOL, "登录跳转次数过多")
                page = request(Request.Builder().url(next))
            } else {
                throw SsoParser.loginFailure(page.body)
            }
        }
        fail(ErrorKind.AUTH, "登录回调中未取得签到凭证")
    }

    override fun refresh(): Snapshot {
        val data = api("init.action", post = true)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: fail(ErrorKind.PROTOCOL, "学校未返回签到计划")
        val user = data.obj("userData")
        val params = data.obj("paramsData")
        val status = data.obj("statusData")
        val stamp = api("currentTimestamp.action")?.takeIf { it.isJsonObject }?.asJsonObject?.text("timestamp").orEmpty()
        val (now, serverTime) = TimeRules.fromTimestamp(stamp, clock)
        val way = user.text("FWay").toIntOrNull()
        val start = params.text("FStartTime")
        val end = params.text("FEndTime")
        val state = when {
            status.text("status") == "SUCCESS" -> CheckinStatus.SUCCESS
            status.text("status") == "PROCESSING" -> CheckinStatus.PROCESSING
            user.text("FPlanID").isBlank() -> CheckinStatus.NO_PLAN
            way == 3 -> CheckinStatus.NEED_NOT
            way !in listOf(1, 2) -> CheckinStatus.UNKNOWN_WAY
            !TimeRules.inRange(now, start, end) -> CheckinStatus.OUT_OF_TIME
            way == 2 -> CheckinStatus.NEEDS_ADDRESS
            else -> CheckinStatus.READY
        }
        if (state == CheckinStatus.SUCCESS || state == CheckinStatus.PROCESSING) pendingSubmission = false
        val finalState = if (pendingSubmission && state in listOf(CheckinStatus.READY, CheckinStatus.NEEDS_ADDRESS)) CheckinStatus.UNKNOWN_RESULT else state
        val id = user.text("FDefaultCampus")
        val school = schools(data).firstOrNull { it.text("ID") == id }
        val attn = status.obj("attn")
        return Snapshot(user.text("FName"), user.text("FCollUnit"), user.text("FPlanID"), way, start, end,
            params.text("FLateTime"), now, serverTime, finalState, Campus(id, school?.text("FName").orEmpty()),
            status.text("checkInTime").ifBlank { attn.text("checkInTime") },
            status.text("actualLocation").ifBlank { attn.text("actualLocation") }, data)
    }

    override fun submit(address: String, onConfirming: () -> Unit): SubmitOutcome {
        if (pendingSubmission) return SubmitOutcome(refresh(), "上次提交结果尚未确认，本次未重复提交")
        val before = refresh()
        if (before.status !in listOf(CheckinStatus.READY, CheckinStatus.NEEDS_ADDRESS)) return SubmitOutcome(before)
        if (config.latitude == 0.0 || config.longitude == 0.0) fail(ErrorKind.VALIDATION, "签到坐标未配置")
        val campus = when (before.way) {
            1 -> checkLocation(before.raw)
            2 -> {
                if (address.isBlank()) fail(ErrorKind.VALIDATION, "学校指定校外签到，请填写地址")
                before.campus
            }
            else -> fail(ErrorKind.VALIDATION, "学校返回了暂不支持的签到方式")
        }
        val location = listOf(campus.title, address.trim()).filter { it.isNotBlank() }.joinToString(" ")
        val payload = linkedMapOf<String, Any>(
            "campus" to campus.id, "lon" to config.longitude, "lat" to config.latitude,
            "startTime" to before.start, "endTime" to before.end, "actualLocation" to location,
            "caIsNo" to config.caIsNo, "collUnit" to before.college, "way" to before.way!!,
            "isLate" to TimeRules.isLate(before.serverNow, before.late, before.start, before.end),
        )
        // Set before issuing the mutation, including HTTP GET: a lost response is not a failed submission.
        pendingSubmission = true
        var submitted: JsonObject? = null
        var requestFailed = false
        try { submitted = api("clockIn.action", payload)?.takeIf { it.isJsonObject }?.asJsonObject }
        catch (_: CheckinException) { requestFailed = true }
        onConfirming()
        val confirmed = try { refresh() } catch (_: CheckinException) { null }
        if (confirmed?.status == CheckinStatus.SUCCESS) {
            val attn = submitted?.obj("attn") ?: JsonObject()
            return SubmitOutcome(confirmed.copy(campus = campus,
                checkInTime = confirmed.checkInTime.ifBlank { attn.text("checkInTime") },
                actualLocation = confirmed.actualLocation.ifBlank { attn.text("actualLocation") }))
        }
        if (confirmed?.status == CheckinStatus.PROCESSING) return SubmitOutcome(confirmed)
        return SubmitOutcome((confirmed ?: before).copy(status = CheckinStatus.UNKNOWN_RESULT),
            if (requestFailed) "提交响应未能确认，请刷新状态；不会自动重复签到" else "服务器尚未确认签到结果，请稍后刷新")
    }

    private fun schools(data: JsonObject): List<JsonObject> = data.get("schoolData")
        ?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()

    private fun checkLocation(data: JsonObject): Campus {
        val ranges = JsonArray()
        for (school in schools(data)) {
            try {
                val parsed = JsonParser.parseString(school.text("FPosition")).asJsonObject
                parsed.get("range")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach(ranges::add)
            } catch (_: Exception) { /* Skip malformed optional school ranges as the Python client does. */ }
        }
        val range = JsonObject().apply { add("range", ranges) }
        val result = api("check.action", linkedMapOf("mapType" to "amap", "longitude" to config.longitude,
            "latitude" to config.latitude, "range" to range.toString()))
        val first = result?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: fail(ErrorKind.VALIDATION, "学校判断配置坐标不在允许签到的范围内")
        return Campus(first.text("id"), first.text("title").ifBlank { first.text("name") })
    }

    private fun api(name: String, payload: Map<String, Any>? = null, post: Boolean = false): com.google.gson.JsonElement? {
        val currentToken = token ?: fail(ErrorKind.AUTH, "登录已失效，请重新登录")
        val url = base.resolve("/livecloud/project/fzu/attn/$name")!!.newBuilder().apply {
            if (payload != null) addQueryParameter("param", ProtocolCrypto.encodeURIComponent(ProtocolCrypto.encryptPayload(payload)))
        }.build()
        val builder = Request.Builder().url(url).header("token", currentToken)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", base.resolve("/plug-in/livecloud/project/fzu/attn/index.action")!!.toString())
        if (post) builder.post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
        val response = request(builder)
        if (response.code in listOf(401, 403) || response.code in 300..399) fail(ErrorKind.AUTH, "登录已失效，请重新登录")
        if (response.code !in 200..299) fail(ErrorKind.NETWORK, "学校服务暂不可用（HTTP ${response.code}），请稍后刷新")
        val root = try { JsonParser.parseString(response.body).asJsonObject } catch (_: Exception) {
            if (response.body.contains("name=\"username\"") || response.body.contains("sso.fzu.edu.cn")) fail(ErrorKind.AUTH, "登录已失效，请重新登录")
            fail(ErrorKind.PROTOCOL, "学校接口响应格式发生变化，请稍后重试")
        }
        val ok = root.get("success")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        val code = root.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        if (!ok || code != 1) {
            val msg = root.text("msg")
            if (listOf("登录", "失效", "过期", "token").any { msg.contains(it, ignoreCase = true) }) fail(ErrorKind.AUTH, "登录已失效，请重新登录")
            fail(ErrorKind.PROTOCOL, "学校接口未接受请求，请刷新状态或重新登录")
        }
        return root.get("data")?.takeUnless { it.isJsonNull }
    }

    private class Page(val code: Int, val url: HttpUrl, val location: HttpUrl?, val body: String)
    private fun request(builder: Request.Builder): Page {
        val url = builder.build().url
        val trusted = url.host == base.host || url.host in config.ssoHosts
        if (!trusted || (!url.isHttps && !config.allowHttpForTests)) fail(ErrorKind.PROTOCOL, "已停止访问非预期的认证地址")
        return try {
            client.newCall(builder.header("User-Agent", userAgent).header("Accept-Language", "zh-CN,zh;q=0.9").build()).execute().use {
                Page(it.code, it.request.url, it.header("Location")?.let(url::resolve), it.body?.string().orEmpty())
            }
        } catch (_: IOException) { fail(ErrorKind.NETWORK, "网络连接失败或超时，请检查网络后刷新") }
    }

    private fun findToken(page: Page): String? = (page.url.queryParameter("token") ?: page.location?.queryParameter("token"))?.takeIf { it.isNotBlank() }
    private fun fail(kind: ErrorKind, message: String): Nothing = throw CheckinException(kind, message)
}
