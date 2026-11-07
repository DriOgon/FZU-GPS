package com.driogon.fzucheckin.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class ProtocolTest {
    private val fixture = JsonParser.parseString(javaClass.getResource("/crypto-vectors.json")!!.readText()).asJsonObject

    @Test fun passwordEncryptionMatchesPythonForDesAndAllAesKeyLengths() {
        fixture.getAsJsonArray("passwordVectors").forEach {
            val v = it.asJsonObject
            assertEquals(v.get("encrypted").asString, ProtocolCrypto.encryptPassword(v.get("password").asString, v.get("key").asString))
        }
    }

    @Test fun payloadAndFirstUrlEncodingMatchPythonIncludingUnicodeAndHtmlCharacters() {
        val json = fixture.getAsJsonObject("payload")
        val payload = linkedMapOf<String, Any>()
        json.entrySet().forEach { (k, v) ->
            payload[k] = if (v.asJsonPrimitive.isNumber) {
                if (k in listOf("way", "isLate")) v.asInt else v.asDouble
            } else v.asString
        }
        val encrypted = ProtocolCrypto.encryptPayload(payload)
        assertEquals(fixture.get("encrypted").asString, encrypted)
        assertEquals(fixture.get("encoded").asString, ProtocolCrypto.encodeURIComponent(encrypted))
    }

    @Test fun invalidLoginKeysProduceControlledError() {
        for (key in listOf("not base64", "YWJj")) {
            val e = assertThrows(CheckinException::class.java) { ProtocolCrypto.encryptPassword("synthetic", key) }
            assertEquals(ErrorKind.PROTOCOL, e.kind)
        }
    }

    @Test fun parsesDynamicFormWithoutLoggingItsValues() {
        val form = SsoParser.parse("""<form action="/login"><input name="username"><input name="execution" value="flow"><input name="croypto" value="MDEyMzQ1Njc4OWFiY2RlZg=="><input name="hidden" value="kept"></form>""")
        assertEquals("flow", form.execution)
        assertEquals("kept", form.fields["hidden"])
        assertEquals("UsernamePassword", form.loginType)
    }

    @Test fun captchaAndChangedPagesStopLogin() {
        assertEquals(ErrorKind.CAPTCHA, assertThrows(CheckinException::class.java) {
            SsoParser.parse("<input id='captcha-url' value='/captcha'>")
        }.kind)
        assertEquals(ErrorKind.PROTOCOL, assertThrows(CheckinException::class.java) { SsoParser.parse("<html>changed</html>") }.kind)
    }

    @Test fun timeWindowIncludesEndMinuteAndHandlesMidnight() {
        fun at(t: String) = ZonedDateTime.parse("2026-11-06T$t+08:00[Asia/Shanghai]")
        assertTrue(TimeRules.inRange(at("23:00:59"), "21:00", "23:00"))
        assertFalse(TimeRules.inRange(at("23:01:00"), "21:00", "23:00"))
        assertTrue(TimeRules.inRange(at("00:15:00"), "23:00", "00:30"))
        assertFalse(TimeRules.inRange(at("12:00:00"), "23:00", "00:30"))
        assertFalse(TimeRules.inRange(at("21:00:00"), "invalid", "23:00"))
    }

    @Test fun latenessAcrossMidnightUsesThePlanDay() {
        fun at(t: String) = ZonedDateTime.parse("2026-11-06T$t+08:00[Asia/Shanghai]")
        assertEquals(1, TimeRules.isLate(at("00:10:00"), "23:30", "23:00", "00:30"))
        assertEquals(0, TimeRules.isLate(at("23:50:00"), "00:10", "23:00", "00:30"))
        assertEquals(1, TimeRules.isLate(at("00:20:00"), "00:10", "23:00", "00:30"))
    }

    @Test fun schoolTimesWithPaddingAreParsedLikePythonIntegers() {
        val now = ZonedDateTime.parse("2026-11-07T21:30:00+08:00[Asia/Shanghai]")
        assertTrue(TimeRules.inRange(now, "21:00      ", "23:59     "))
        assertTrue(TimeRules.inRange(now, " \t21 : 00:00 ", "23:59:00\u00a0"))
        assertEquals(0, TimeRules.isLate(now, "22:00     ", "21:00      ", "23:59     "))
        assertEquals(1, TimeRules.isLate(now, "21:00     ", "20:00      ", "23:59     "))
        assertFalse(TimeRules.inRange(now, "25:00      ", "23:59     "))
    }

    @Test fun serverTimestampSupportsSecondsMillisecondsAndFallback() {
        val now = ZonedDateTime.parse("2026-11-06T21:30:00+08:00[Asia/Shanghai]")
        val seconds = now.toEpochSecond()
        assertEquals(now.toInstant(), TimeRules.fromTimestamp(seconds.toString()) { now }.first.toInstant())
        assertEquals(now.toInstant(), TimeRules.fromTimestamp((seconds*1000).toString()) { now }.first.toInstant())
        assertFalse(TimeRules.fromTimestamp("invalid") { now }.second)
    }
}
