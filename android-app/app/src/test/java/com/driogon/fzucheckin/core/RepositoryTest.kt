package com.driogon.fzucheckin.core

import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RepositoryTest {
    private val server = MockWebServer()
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var repo: FzuRepository
    private var way = 1
    private var status = ""
    private var plan = "test-plan"
    private var start = "21:00"
    private var end = "23:00"
    private var afterSubmit = "SUCCESS"
    private var locationAllowed = true
    private var disconnectSubmit = false
    private var badJson = false
    private var authExpired = false
    private var timestamp = ZonedDateTime.parse("2026-11-06T21:30:00+08:00").toInstant().toEpochMilli().toString()

    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/authorize" -> MockResponse().setResponseCode(302).setHeader("Location", "/callback?token=synthetic-token")
                    path.endsWith("init.action") -> when {
                        authExpired -> MockResponse().setResponseCode(401)
                        badJson -> MockResponse().setBody("<html>not json</html>")
                        else -> ok("""{"userData":{"FName":"测试同学","FCollUnit":"测试学院","FPlanID":"$plan","FWay":$way,"FDefaultCampus":"c1"},"paramsData":{"FStartTime":"$start","FEndTime":"$end","FLateTime":"22:00"},"statusData":{"status":"$status"},"schoolData":[{"ID":"c1","FName":"测试校区","FPosition":"{\"range\":[]}"}]}""")
                    }
                    path.endsWith("currentTimestamp.action") -> ok("""{"timestamp":"$timestamp"}""")
                    path.endsWith("check.action") -> ok(if (locationAllowed) """[{"id":"c1","title":"测试校区"}]""" else "[]")
                    path.endsWith("clockIn.action") -> {
                        status = afterSubmit
                        if (disconnectSubmit) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        else ok("""{"attn":{"checkInTime":"21:30:00","actualLocation":"测试校区"}}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        repo = FzuRepository(ServiceConfig(baseUrl = server.url("/").toString(), authorizeUrl = server.url("/authorize").toString(),
            ssoHosts = setOf(server.hostName), allowHttpForTests = true))
        repo.login(Credentials("synthetic-account", "synthetic-password"))
    }
    @After fun teardown() { repo.clearSession(); server.shutdown() }
    private fun ok(data: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true,"code":1,"data":$data}""")
    private fun submissions() = calls.count { it.requestUrl!!.encodedPath.endsWith("clockIn.action") }

    @Test fun refreshOnlyQueriesAndNeverSignsIn() {
        assertEquals(CheckinStatus.READY, repo.refresh().status)
        assertEquals(0, submissions())
        assertTrue(calls.filter { it.requestUrl!!.encodedPath.contains("attn/") }.all { it.getHeader("token") == "synthetic-token" })
    }

    @Test fun schoolModeChecksConfiguredPointAndConfirmsSuccess() {
        var confirming = false
        val outcome = repo.submit("") { confirming = true }
        assertTrue(confirming)
        assertEquals(CheckinStatus.SUCCESS, outcome.snapshot.status)
        assertEquals("21:30:00", outcome.snapshot.checkInTime)
        assertEquals(1, submissions())
        val check = calls.first { it.requestUrl!!.encodedPath.endsWith("check.action") }
        val submitted = calls.first { it.requestUrl!!.encodedPath.endsWith("clockIn.action") }
        assertEquals(119.19, decode(check).get("longitude").asDouble, 0.0)
        assertEquals(26.05, decode(submitted).get("lat").asDouble, 0.0)
        assertEquals(1, decode(submitted).get("way").asInt)
        assertTrue(submitted.requestUrl!!.encodedQuery!!.contains("%25"))
    }

    private fun decode(request: RecordedRequest): com.google.gson.JsonObject {
        val encrypted = URLDecoder.decode(request.requestUrl!!.queryParameter("param")!!, "UTF-8")
        val key = "apexinfoapexinfo".toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        return JsonParser.parseString(cipher.doFinal(Base64.getDecoder().decode(encrypted)).toString(Charsets.UTF_8)).asJsonObject
    }

    @Test fun outsideModeRequiresAddressAndDoesNotUseLocationCheck() {
        way = 2
        assertEquals(CheckinStatus.NEEDS_ADDRESS, repo.refresh().status)
        assertEquals(ErrorKind.VALIDATION, assertThrows(CheckinException::class.java) { repo.submit("") }.kind)
        assertEquals(0, submissions())
        assertEquals(CheckinStatus.SUCCESS, repo.submit("测试地址").snapshot.status)
        assertFalse(calls.any { it.requestUrl!!.encodedPath.endsWith("check.action") })
    }

    @Test fun processingIsNotReportedAsSuccess() {
        afterSubmit = "PROCESSING"
        assertEquals(CheckinStatus.PROCESSING, repo.submit("").snapshot.status)
    }

    @Test fun unconfirmedResponseCannotBeReplayedEvenAfterLoginRefresh() {
        afterSubmit = ""
        assertEquals(CheckinStatus.UNKNOWN_RESULT, repo.submit("").snapshot.status)
        repo.login(Credentials("synthetic-account", "synthetic-password"))
        assertEquals(CheckinStatus.UNKNOWN_RESULT, repo.submit("").snapshot.status)
        assertEquals(1, submissions())
    }

    @Test fun lostResponseIsConfirmedWithoutResending() {
        disconnectSubmit = true
        assertEquals(CheckinStatus.SUCCESS, repo.submit("").snapshot.status)
        assertEquals(1, submissions())
    }

    @Test fun earlyExitConditionsNeverSubmit() {
        for ((w, st, p, expected) in listOf(
            listOf(1, "SUCCESS", "test-plan", CheckinStatus.SUCCESS),
            listOf(1, "PROCESSING", "test-plan", CheckinStatus.PROCESSING),
            listOf(3, "", "test-plan", CheckinStatus.NEED_NOT),
            listOf(1, "", "", CheckinStatus.NO_PLAN),
            listOf(9, "", "test-plan", CheckinStatus.UNKNOWN_WAY),
        )) {
            way = w as Int; status = st as String; plan = p as String
            assertEquals(expected, repo.submit("").snapshot.status)
        }
        start = "18:00"; end = "19:00"; way = 1; status = ""; plan = "test-plan"
        assertEquals(CheckinStatus.OUT_OF_TIME, repo.submit("").snapshot.status)
        assertEquals(0, submissions())
    }

    @Test fun badLocationStopsBeforeSubmission() {
        locationAllowed = false
        assertEquals(ErrorKind.VALIDATION, assertThrows(CheckinException::class.java) { repo.submit("") }.kind)
        assertEquals(0, submissions())
    }

    @Test fun expiredLoginAndMalformedApiResponsesAreControlledErrors() {
        authExpired = true
        assertEquals(ErrorKind.AUTH, assertThrows(CheckinException::class.java) { repo.refresh() }.kind)
        authExpired = false; badJson = true
        assertEquals(ErrorKind.PROTOCOL, assertThrows(CheckinException::class.java) { repo.refresh() }.kind)
    }

    @Test fun missingTimestampIsLabelledAsLocalFallback() {
        timestamp = "invalid"
        assertFalse(repo.refresh().serverTimeAvailable)
    }

    @Test fun clearingAccountPreventsRequestsWithPreviousToken() {
        repo.clearSession()
        assertEquals(ErrorKind.AUTH, assertThrows(CheckinException::class.java) { repo.refresh() }.kind)
        assertEquals(1, calls.size)
    }
}
