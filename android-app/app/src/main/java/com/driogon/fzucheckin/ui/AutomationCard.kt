package com.driogon.fzucheckin.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.driogon.fzucheckin.automation.AutoTimes
import com.driogon.fzucheckin.core.TimeRules
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun AutomationCard(state: AppState, onConfigure: (Boolean, Int) -> Unit) {
    val settings = state.automation
    val context = LocalContext.current
    val notificationPrefs = remember(context) { context.getSharedPreferences("notification_ui", android.content.Context.MODE_PRIVATE) }
    val owner = LocalLifecycleOwner.current
    var draftEnabled by remember(settings.version) { mutableStateOf(settings.enabled) }
    var minute by remember(settings.version) { mutableIntStateOf(settings.minute) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var pending by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        pending?.let { value -> onConfigure(value.first, value.second) }; pending = null
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME)
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun save(enabled: Boolean, time: Int) {
        if (enabled && !settings.enabled && Build.VERSION.SDK_INT >= 33 && !notifications && !notificationPrefs.getBoolean("asked", false)) {
            notificationPrefs.edit().putBoolean("asked", true).apply()
            pending = enabled to time; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else onConfigure(enabled, time)
    }
    fun openSettings(action: String, usePackage: Boolean) {
        runCatching {
            context.startActivity(Intent(action).apply {
                if (usePackage) data = Uri.parse("package:${context.packageName}")
                else putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            })
        }.onFailure { Toast.makeText(context, "无法打开设置，请在系统应用管理中调整", Toast.LENGTH_SHORT).show() }
    }
    Surface(color = androidx.compose.ui.graphics.Color.White, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动签到", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(draftEnabled, { checked ->
                    if (!checked && settings.enabled) onConfigure(false, settings.minute)
                    draftEnabled = checked
                }, enabled = !state.busy && (state.credentialSaved || settings.enabled), modifier = Modifier.testTag("autoSwitch"))
            }
            Text("每天按北京时间执行，无需打开 App；系统可能延迟。", style = MaterialTheme.typography.bodySmall, color = Muted)
            if (!state.credentialSaved) Text("账号成功加密保存后才能开启", style = MaterialTheme.typography.bodySmall, color = Warning)
            TextButton(onClick = {
                TimePickerDialog(context, { _, h, m -> minute = h * 60 + m }, minute / 60, minute % 60, true).show()
            }, enabled = !state.busy, modifier = Modifier.testTag("autoTime")) {
                Text("每日执行时间  %02d:%02d".format(minute / 60, minute % 60))
            }
            if (draftEnabled != settings.enabled || minute != settings.minute) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save(draftEnabled, minute) }, enabled = !state.busy && (!draftEnabled || state.credentialSaved),
                        modifier = Modifier.testTag("autoSave")) { Text("保存设置") }
                    TextButton(onClick = { minute = settings.minute; draftEnabled = settings.enabled }) { Text("取消") }
                }
            }
            if (settings.paused.isNotEmpty()) {
                Text("已暂停：${settings.paused}", color = Warning, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { save(true, settings.minute) }, enabled = state.credentialSaved && !state.busy) { Text("恢复自动签到") }
            } else if (settings.active) {
                val now = ZonedDateTime.now(TimeRules.china)
                val next = AutoTimes.next(now, settings.minute)
                Text("预计于 ${next.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))} 开始，可能延迟", style = MaterialTheme.typography.bodySmall)
            }
            Text("最近结果：${settings.lastMessage}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("autoResult"))
            if (!notifications) Text("通知未开启，无法收到执行结果通知", color = Warning, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS, false) }, modifier = Modifier.weight(1f)) { Text("通知设置") }
                TextButton(onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }, modifier = Modifier.weight(1f)) { Text("后台运行设置") }
            }
            Text("请在系统中允许后台运行。强制停止后需重新打开 App；校外方式需手动处理。", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}
