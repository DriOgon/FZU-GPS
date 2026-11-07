package com.driogon.fzucheckin.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.driogon.fzucheckin.BuildConfig
import com.driogon.fzucheckin.core.*
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FzuApp(
    state: AppState,
    onLogin: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onSubmit: (String) -> Unit,
    onClear: () -> Unit,
) {
    var tab by rememberSaveable(state.loggedIn) { mutableIntStateOf(0) }
    var address by remember(state.loggedIn) { mutableStateOf("") }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = if (state.loggedIn) PageBackground else Color.White,
        bottomBar = {
            if (state.loggedIn) {
                Column {
                    HorizontalDivider(color = DividerColor)
                    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                        listOf("签到" to Icons.Outlined.CheckCircle, "我的" to Icons.Outlined.Person).forEachIndexed { i, item ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i },
                                icon = { Icon(item.second, contentDescription = null) }, label = { Text(item.first) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = Blue, selectedTextColor = Blue,
                                    unselectedIconColor = Muted, unselectedTextColor = Muted, indicatorColor = Color.Transparent))
                        }
                    }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        if (!state.loggedIn) LoginScreen(state, onLogin, modifier)
        else if (tab == 0) HomeScreen(state, address, { address = it }, onRefresh, onSubmit, modifier)
        else ProfileScreen(state, onClear, modifier)
    }
}

@Composable
private fun LoginScreen(state: AppState, onLogin: (String, String) -> Unit, modifier: Modifier) {
    // Credentials are intentionally not rememberSaveable: no password in saved-instance bundles.
    var username by remember(state.username) { mutableStateOf(state.username) }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(52.dp))
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Blue), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("福大签到助手", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text("使用统一身份认证账号登录", color = Muted)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth().testTag("username"),
            label = { Text("账号") }, singleLine = true, enabled = !state.busy, shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next))
        OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth().testTag("password"),
            label = { Text("密码") }, singleLine = true, enabled = !state.busy, shape = RoundedCornerShape(10.dp),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏密码" else "显示密码")
            } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!state.busy) onLogin(username, password) }))
        state.error?.let { Message(it, Failure) }
        Button(onClick = { onLogin(username, password) }, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("login"), shape = RoundedCornerShape(10.dp)) {
            if (state.busy) BusyLabel("正在登录") else Text("登录")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = Muted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("账号密码加密保存在本机", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.height(32.dp))
        Text("非官方校园工具", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodySmall, color = Muted)
        Spacer(Modifier.height(20.dp))
    }
}

private data class StatusPresentation(val title: String, val description: String, val color: Color, val icon: ImageVector)
private fun presentation(status: CheckinStatus?) = when (status) {
    CheckinStatus.SUCCESS -> StatusPresentation("签到成功", "已从服务器确认今日签到", Success, Icons.Outlined.CheckCircle)
    CheckinStatus.PROCESSING -> StatusPresentation("签到处理中", "请稍后刷新查看结果", Warning, Icons.Outlined.Schedule)
    CheckinStatus.NO_PLAN -> StatusPresentation("当前没有签到计划", "学校暂未分配签到计划", Muted, Icons.Outlined.Info)
    CheckinStatus.NEED_NOT -> StatusPresentation("当前无需签到", "以学校返回的安排为准", Success, Icons.Outlined.CheckCircle)
    CheckinStatus.OUT_OF_TIME -> StatusPresentation("当前不在签到时间内", "请在下方所示时间内操作", Warning, Icons.Outlined.Schedule)
    CheckinStatus.NEEDS_ADDRESS -> StatusPresentation("今日未签到", "学校指定校外签到，请填写地址", Blue, Icons.Outlined.Info)
    CheckinStatus.UNKNOWN_WAY -> StatusPresentation("暂不支持此签到方式", "请使用学校官方入口处理", Failure, Icons.Outlined.ErrorOutline)
    CheckinStatus.UNKNOWN_RESULT -> StatusPresentation("签到结果待确认", "请刷新核实，本次不会重复提交", Warning, Icons.Outlined.Schedule)
    CheckinStatus.READY -> StatusPresentation("今日未签到", "在允许的时间内点击签到", Blue, Icons.Outlined.Schedule)
    null -> StatusPresentation("尚未取得签到状态", "请刷新获取学校的最新安排", Muted, Icons.Outlined.Info)
}

@Composable
private fun HomeScreen(state: AppState, address: String, onAddress: (String) -> Unit,
    onRefresh: () -> Unit, onSubmit: (String) -> Unit, modifier: Modifier) {
    val snapshot = state.snapshot
    val status = presentation(snapshot?.status)
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("晚点名", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.testTag("refresh")) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("刷新", fontSize = 14.sp)
            }
        }
        Text((snapshot?.serverNow ?: ZonedDateTime.now(TimeRules.china)).format(DateTimeFormatter.ofPattern("M月d日  EEEE", Locale.CHINA)),
            style = MaterialTheme.typography.bodySmall, color = Muted)
        Text("你好，${snapshot?.name?.ifBlank { "同学" } ?: "同学"}", style = MaterialTheme.typography.headlineSmall)
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(status.icon, null, tint = status.color, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp)); Text(status.title, color = status.color, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            Text(status.description, style = MaterialTheme.typography.bodySmall, color = Muted)
            if (snapshot?.status == CheckinStatus.SUCCESS) {
                if (snapshot.checkInTime.isNotBlank()) Text("时间：${snapshot.checkInTime}", style = MaterialTheme.typography.bodyMedium)
                if (snapshot.actualLocation.isNotBlank()) Text("地点：${snapshot.actualLocation}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (snapshot != null) {
            Group {
                InfoRow("签到时间", if (snapshot.start.isBlank() || snapshot.end.isBlank()) "暂无" else "${snapshot.start.trim()} — ${snapshot.end.trim()}")
                HorizontalDivider(color = DividerColor)
                InfoRow("签到方式", snapshot.wayLabel)
                HorizontalDivider(color = DividerColor)
                InfoRow("所属学院", snapshot.college.ifBlank { "暂无" })
            }
            Group {
                Text("签到地点", style = MaterialTheme.typography.bodyMedium, color = Muted)
                Spacer(Modifier.height(8.dp))
                Text(snapshot.actualLocation.ifBlank { snapshot.campus.title.ifBlank { "提交前由学校校验" } })
                Spacer(Modifier.height(8.dp))
                Text("使用配置坐标", style = MaterialTheme.typography.bodySmall, color = Muted)
                if (snapshot.way == 2 && snapshot.status in listOf(CheckinStatus.NEEDS_ADDRESS, CheckinStatus.READY)) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(address, onAddress, Modifier.fillMaxWidth().testTag("address"),
                        label = { Text("签到地址") }, supportingText = { Text("学校指定校外签到时必填") },
                        enabled = !state.busy, shape = RoundedCornerShape(10.dp), maxLines = 3)
                }
            }
            if (!snapshot.serverTimeAvailable) Message("服务器时间暂不可用，本次使用手机北京时间，请确认手机时间准确", Warning)
        }
        state.error?.let { Message(it, Failure) }
        state.notice?.let { Message(it, Warning) }
        val eligible = snapshot?.status in listOf(CheckinStatus.READY, CheckinStatus.NEEDS_ADDRESS)
        val addressReady = snapshot?.way != 2 || address.isNotBlank()
        val buttonText = when (state.phase) {
            BusyPhase.SUBMIT -> "正在签到"
            BusyPhase.CONFIRM -> "正在确认结果"
            BusyPhase.LOGIN -> "正在登录"
            BusyPhase.REFRESH -> "正在查询"
            else -> when {
                snapshot?.status == CheckinStatus.SUCCESS -> "已签到"
                eligible -> "签到"
                else -> "刷新状态"
            }
        }
        Button(onClick = { if (eligible) onSubmit(address) else onRefresh() },
            enabled = !state.busy && addressReady && snapshot?.status != CheckinStatus.SUCCESS,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("checkin"), shape = RoundedCornerShape(10.dp)) {
            if (state.busy) BusyLabel(buttonText) else Text(buttonText)
        }
        snapshot?.let {
            Text("最近查询 ${it.serverNow.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}",
                Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ProfileScreen(state: AppState, onClear: () -> Unit, modifier: Modifier) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("我的", style = MaterialTheme.typography.titleLarge)
        Group {
            Text("当前账号", style = MaterialTheme.typography.bodySmall, color = Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(maskAccount(state.username), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { dialog = "switch" }, enabled = !state.busy) { Text("更换账号", fontSize = 14.sp) }
            }
        }
        Group(contentPadding = 0) {
            SettingsRow("清除本机数据", !state.busy) { dialog = "clear" }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = DividerColor)
            SettingsRow("使用说明") { dialog = "help" }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = DividerColor)
            SettingsRow("源码仓库") {
                runCatching { uri.openUri("https://github.com/DriOgon/FZU-GPS") }
                    .onFailure { Toast.makeText(context, "未找到可打开网页的应用", Toast.LENGTH_SHORT).show() }
            }
        }
        Group {
            Text("关于福大签到助手", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(Modifier.height(8.dp))
            Text("非学校或福 uu 官方客户端", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        state.error?.let { Message(it, Failure) }
    }
    if (dialog != null) {
        val help = dialog == "help"
        AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(if (help) "使用说明" else if (dialog == "switch") "更换账号" else "清除本机数据") },
            text = { Text(if (help) "打开 App 会查询当天计划，不会自动签到。\n\n在学校允许的时间点击签到；提交后会再次查询确认。\n\n遇到验证码请稍后重试。提交超时或显示处理中时先刷新核实，不要重复提交。\n\n位置采用程序配置坐标，签到要求以学校规定为准。"
                else "将清除本机保存的账号密码、登录凭证和当前页面数据。之后需要重新登录。") },
            confirmButton = { TextButton(onClick = { if (!help) onClear(); dialog = null }) { Text(if (help) "知道了" else "确认清除") } },
            dismissButton = { if (!help) TextButton(onClick = { dialog = null }) { Text("取消") } })
    }
}

@Composable
private fun Group(contentPadding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(contentPadding.dp), content = content)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(88.dp), color = Muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingsRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), colors = ButtonDefaults.textButtonColors(contentColor = Ink)) {
        Text(label, modifier = Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun Message(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun BusyLabel(label: String) {
    CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
    Spacer(Modifier.width(8.dp)); Text(label)
}

fun maskAccount(value: String): String = if (value.length > 4) value.take(2) + "*".repeat(value.length - 4) + value.takeLast(2) else "*".repeat(value.length)

fun demoState(status: CheckinStatus = CheckinStatus.READY) = AppState(loggedIn = true, username = "240000001",
    snapshot = Snapshot("张同学", "示例学院", "demo-plan", 1, "21:00", "23:00", "22:00",
        ZonedDateTime.parse("2026-11-06T21:12:08+08:00[Asia/Shanghai]"), true, status, Campus("demo", "示例校区"),
        if (status == CheckinStatus.SUCCESS) "21:12:08（示例）" else ""))

@Preview(name = "登录", widthDp = 390, heightDp = 844, showBackground = true)
@Composable private fun LoginPreview() { FzuTheme { FzuApp(AppState(), { _, _ -> }, {}, {}, {}) } }
@Preview(name = "签到", widthDp = 390, heightDp = 844)
@Composable private fun HomePreview() { FzuTheme { FzuApp(demoState(), { _, _ -> }, {}, {}, {}) } }
@Preview(name = "成功", widthDp = 390, heightDp = 844)
@Composable private fun SuccessPreview() { FzuTheme { FzuApp(demoState(CheckinStatus.SUCCESS), { _, _ -> }, {}, {}, {}) } }
@Preview(name = "处理中", widthDp = 390, heightDp = 844)
@Composable private fun ProcessingPreview() { FzuTheme { FzuApp(demoState(CheckinStatus.PROCESSING), { _, _ -> }, {}, {}, {}) } }
@Preview(name = "失败", widthDp = 390, heightDp = 844)
@Composable private fun FailurePreview() { FzuTheme { FzuApp(demoState().copy(error = "网络连接失败，请刷新后重试"), { _, _ -> }, {}, {}, {}) } }
@Preview(name = "我的", widthDp = 390, heightDp = 844)
@Composable private fun ProfilePreview() { FzuTheme { ProfileScreen(demoState(), {}, Modifier.fillMaxSize().background(PageBackground)) } }
