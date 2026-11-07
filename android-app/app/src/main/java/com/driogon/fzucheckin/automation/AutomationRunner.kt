package com.driogon.fzucheckin.automation

import com.driogon.fzucheckin.core.*
import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime

class AutomationRunner(
    private val coordinator: CheckinCoordinator,
    private val queue: ConfirmationQueue,
    private val notify: (AutoTask, String, Boolean) -> Unit,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(TimeRules.china) },
) {
    private val store get() = coordinator.automation
    private fun finish(task: AutoTask, text: String, attention: Boolean = true, pause: Boolean = false) {
        if (store.message(task, text, pause = pause, finished = true)) notify(task, text, attention)
    }

    /** true means retry the pre-submission query; it never means replay clockIn.action. */
    suspend fun attempt(task: AutoTask): Boolean {
        if (!AutoTimes.current(store.read(), task) || store.finished(task)) return false
        if (!AutoTimes.unexpired(task, clock())) { finish(task, "本次自动签到任务已过期"); return false }
        if (store.count(task) >= 3) { finish(task, "查询重试次数已用完，请打开 App 手动处理"); return false }
        val attempt = store.increment(task)
        try {
            val outcome = coordinator.automatic(task)
            when (outcome.snapshot.status) {
                CheckinStatus.SUCCESS -> finish(task, if (outcome.notice == "已签到，已跳过") outcome.notice else "自动签到成功，学校已确认", false)
                CheckinStatus.PROCESSING, CheckinStatus.UNKNOWN_RESULT -> {
                    val key = outcome.key
                    if (key == null) finish(task, "签到结果待确认，请打开 App 核实")
                    else {
                        if (store.message(task, "签到结果待确认，正在安排查询")) queue.confirmation(task, key, 0)
                    }
                }
                else -> finish(task, outcome.notice ?: "本次未完成签到，请打开 App 手动处理")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: StaleAutoTask) { return false }
        catch (e: CheckinException) {
            if (e.kind in listOf(ErrorKind.AUTH, ErrorKind.CAPTCHA)) {
                finish(task, if (e.kind == ErrorKind.CAPTCHA) "遇到验证码，自动签到已暂停，请重新登录后恢复" else "账号认证不可用，自动签到已暂停，请重新登录后恢复", pause = true)
            } else if (e.kind == ErrorKind.NETWORK && attempt < 3 && AutoTimes.unexpired(task, clock())) {
                store.message(task, "查询暂未完成，等待第 ${attempt + 1} 次尝试")
                return true
            } else finish(task, e.message ?: "自动签到未完成，请手动处理")
        } catch (_: Exception) {
            finish(task, "自动签到未完成；若请求已发出，请先刷新核实，不要重复提交")
        }
        return false
    }

    suspend fun confirm(task: AutoTask, key: String, step: Int) {
        if (!AutoTimes.current(store.read(), task) || store.finished(task)) return
        // Persisted counter also bounds re-execution after a worker/process restart.
        if (store.count(task, true) >= 2) { finish(task, "签到结果待确认，请打开 App 核实"); return }
        store.increment(task, true)
        try {
            val snapshot = coordinator.confirm(task, key)
            if (snapshot.status == CheckinStatus.SUCCESS) { finish(task, "签到成功，学校已确认", false); return }
        } catch (e: CancellationException) { throw e }
        catch (_: StaleAutoTask) { return }
        catch (e: CheckinException) {
            if (e.kind in listOf(ErrorKind.AUTH, ErrorKind.CAPTCHA)) {
                finish(task, "认证不可用，自动签到已暂停；原签到结果请手动核实", pause = true); return
            }
            if (e.kind != ErrorKind.NETWORK) { finish(task, e.message ?: "签到结果请手动核实"); return }
        } catch (_: Exception) { /* Keep this path query-only, including database errors. */ }
        if (step == 0 && store.count(task, true) < 2) queue.confirmation(task, key, 1)
        else finish(task, "签到结果待确认，请打开 App 核实")
    }
}
