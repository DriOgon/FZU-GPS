package com.driogon.fzucheckin.core

import org.jsoup.Jsoup

data class LoginForm(
    val action: String,
    val fields: Map<String, String>,
    val crypto: String,
    val execution: String,
    val loginType: String,
)

object SsoParser {
    fun parse(html: String): LoginForm {
        val doc = Jsoup.parse(html)
        fun value(id: String): String {
            val el = doc.getElementById(id) ?: return ""
            return if (el.tagName() == "input") el.attr("value") else el.text().trim()
        }
        val captcha = value("captcha-url")
        if (captcha.isNotBlank()) throw CheckinException(ErrorKind.CAPTCHA, "学校登录要求验证码，请稍后重试。本版本不会自动处理验证码。")
        val crypto = value("login-croypto").ifBlank { doc.selectFirst("input[name=croypto]")?.attr("value").orEmpty() }
        val execution = doc.selectFirst("input[name=execution]")?.attr("value").orEmpty()
            .ifBlank { value("execution") }.ifBlank { value("login-page-flowkey") }
        if (crypto.isBlank() || execution.isBlank()) throw CheckinException(ErrorKind.PROTOCOL, "学校登录页面发生变化，无法读取必要参数")
        val form = doc.selectFirst("input[name=username]")?.closest("form")
            ?: doc.selectFirst("input[name=execution]")?.closest("form") ?: doc.selectFirst("form")
        val fields = linkedMapOf<String, String>()
        form?.select("input[name]")?.forEach { fields[it.attr("name")] = it.attr("value") }
        return LoginForm(form?.attr("action").orEmpty(), fields, crypto, execution, value("current-login-type").ifBlank { "UsernamePassword" })
    }

    fun loginFailure(html: String): CheckinException {
        val text = Jsoup.parse(html).text()
        return if (text.contains("验证码")) CheckinException(ErrorKind.CAPTCHA, "学校登录要求验证码，请稍后重试")
        else CheckinException(ErrorKind.AUTH, "统一认证登录失败，请检查账号密码后重试")
    }
}
