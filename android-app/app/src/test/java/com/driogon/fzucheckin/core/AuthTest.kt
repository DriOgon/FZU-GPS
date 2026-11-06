package com.driogon.fzucheckin.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class AuthTest {
    private val server = MockWebServer()
    private lateinit var repo: FzuRepository
    private val html = """<form action="/login"><input name="username"><input name="password"><input name="execution" value="flow-test"><input name="croypto" value="MDEyMzQ1Njc4OWFiY2RlZg=="><input name="custom" value="preserve"></form>"""
    @Before fun setup() {
        server.start()
        repo = FzuRepository(ServiceConfig(baseUrl = server.url("/").toString(), authorizeUrl = server.url("/authorize").toString(),
            ssoHosts = setOf(server.hostName), allowHttpForTests = true))
    }
    @After fun teardown() { repo.clearSession(); server.shutdown() }

    @Test fun keepsCookiesHiddenFieldsAndServiceQueryWhileEncryptingPassword() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/login?service=test-service"))
        server.enqueue(MockResponse().setBody(html).setHeader("Set-Cookie", "flow=synthetic-cookie; Path=/; HttpOnly"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/callback?token=synthetic-token"))
        repo.login(Credentials("synthetic-account", "synthetic-password"))
        server.takeRequest(1, TimeUnit.SECONDS)!!
        val get = server.takeRequest(1, TimeUnit.SECONDS)!!
        val post = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", get.method)
        assertEquals("POST", post.method)
        assertEquals("test-service", post.requestUrl!!.queryParameter("service"))
        assertEquals("flow=synthetic-cookie", post.getHeader("Cookie"))
        val fields = post.body.readUtf8().split('&').associate {
            val kv = it.split('=', limit = 2)
            URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
        }
        assertEquals("synthetic-account", fields["username"])
        assertEquals("preserve", fields["custom"])
        assertEquals("flow-test", fields["execution"])
        assertEquals("", fields["geolocation"])
        assertEquals(ProtocolCrypto.encryptPassword("synthetic-password", "MDEyMzQ1Njc4OWFiY2RlZg=="), fields["password"])
        assertNotEquals("synthetic-password", fields["password"])
    }

    @Test fun captchaStopsBeforeSubmittingAnyCredentials() {
        server.enqueue(MockResponse().setBody("<input id='captcha-url' value='/captcha'>"))
        assertEquals(ErrorKind.CAPTCHA, assertThrows(CheckinException::class.java) {
            repo.login(Credentials("synthetic-account", "synthetic-password"))
        }.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun wrongPasswordBecomesControlledAuthenticationFailure() {
        server.enqueue(MockResponse().setBody(html))
        server.enqueue(MockResponse().setBody("账号或密码错误"))
        assertEquals(ErrorKind.AUTH, assertThrows(CheckinException::class.java) {
            repo.login(Credentials("synthetic-account", "synthetic-password"))
        }.kind)
    }

    @Test fun untrustedFormActionNeverReceivesCredentials() {
        server.enqueue(MockResponse().setBody(html.replace("action=\"/login\"", "action=\"https://example.invalid/login\"")))
        assertEquals(ErrorKind.PROTOCOL, assertThrows(CheckinException::class.java) {
            repo.login(Credentials("synthetic-account", "synthetic-password"))
        }.kind)
        assertEquals(1, server.requestCount)
    }
}
