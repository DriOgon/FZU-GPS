# 验证记录

本记录区分设计预览、自动化模拟和真实设备结果，不将模拟测试视为实际签到。

## 测试方法

- 协议：Python 生成虚构测试向量，Kotlin 比较 AES/DES 密码密文、含中文与特殊字符的签到参数、URL 编码。
- 网络：MockWebServer 仅监听本机，覆盖登录表单和重定向、Cookie、验证码、密码错误、各签到模式、状态、时间、位置失败、断开响应与过期凭证。
- 状态：验证启动只查询、重复点击只提交一次、过期时只重新查询、更换账号清空旧状态。
- 界面：Robolectric 运行真实 Compose 组件，覆盖输入、密码可见性、导航、按钮禁用、地址必填及大字体。Windows 模拟环境的截图回调超时，因此截图另由真机仪器测试生成。
- 真机存储：`CredentialStoreDeviceTest` 使用独立测试命名空间验证 Android Keystore 凭据保存、重建、密文和清除，不碰真实账号存储。

## 首版执行结果（1.0.0）

- 32 项 JVM 自动测试通过：登录 4、加密/时间/解析 8、签到协议 11、ViewModel 4、Compose 交互 5；0 失败、0 错误。
- 一台 PLJ110、Android 16 / API 36 真机上，8 项仪器测试通过：加密存储 1、界面与截图 7；0 失败。
- 真机测试中的姓名、账号、计划和地址均为虚构；没有构造学校网络客户端，也没有提交真实签到。
- 七张真机截图已通过测试框架导出到 `docs/previews/device/`，已检查文字、按钮和布局。
- 首次 Windows 测试失败来自中文路径的 JVM 参数文件兼容问题，构建脚本通过临时英文盘符解决；模拟截图回调超时改为真机截图，没有跳过交互测试。
- Android 8.0 主题兼容问题已修正，Debug 与 Release Lint 均通过（0 错误；保留依赖更新、图标和 Kotlin 风格建议等非阻断警告）。
- 最终 `Check` 任务复跑通过：Debug APK、32 项自动测试和 Lint。`Release` 任务通过：R8 压缩、签名、Release Lint。
- Release 1.0.0 已安装到上述 Android 16 手机并成功冷启动，应用进程正常；设备确认 versionCode 1、minSdk 26、targetSdk 36。
- APK 经 Android apksigner 验证，APK Signature Scheme v2 有效；RSA 3072 位签名。
- 发布内容及 APK 中均未发现本地原始脚本的个人账号密码；签名私钥与密码配置未打包，且排除 Git。

## 本地交付文件

- `deliverables/FZU-GPS-1.0.0.apk`，1,731,889 字节，作为独立文件交付，不进入源码历史。
- APK SHA-256：`736097582c375306363494c0be77b46b79f508e5678b358cc0a70b1c31b2eb88`。
- 签名证书 SHA-256：`43b5fc56f4c54e4f4b6e593db5818fd54547e7632192b63d35d089b92c4601f8`。
- `deliverables/SHA256SUMS.txt` 与中文安装说明一并提供。
- 后续更新必须保留 `.signing` 中的同一签名，私下备份，绝不上传或随 APK 分享。

## 尚未完成的验收

- 第二台 Android 手机的兼容性验证。
- 验证码的真实出现情形。

## 1.0.1 修复验证（2026-11-07）

- 在连接的 Android 16 手机上复现：处于学校返回的 `21:00      ` 至 `23:59     ` 时段内，旧版仍显示不在签到时间内。
- 根因：移植后 Kotlin `String.toInt()` 不兼容字段空格，而 Python `int()` 会接受首尾空格，解析失败被判定为时间范围外。
- 增加两项回归测试，修复前均失败；修复后共 34 项 JVM 测试全部通过。覆盖带空格时段、迟到时间、无效时刻、完整提交及原始参数保留。
- Debug 与 Release 构建和 Lint 全部通过；发布签名身份与 1.0.0 一致。
- 已通过 USB 从 1.0.0 覆盖升级到 1.0.1（versionCode 2），账号数据保留，自动登录与真实计划查询成功。
- 升级后手机显示“今日未签到”、`21:00 — 23:59` 和可用的签到操作，时间误判已消除。
- 用户在手机手动点击后，已只读核实正式版显示“签到成功”“已从服务器确认今日签到”和“已签到”，真实签到与服务器确认流程通过。
- 新 APK：`deliverables/FZU-GPS-1.0.1.apk`，1,731,889 字节。
- APK SHA-256：`075086bc70466de10812a4c2d8cb798a72c20c87f0bbe59951a8da2288eb9926`。

## 1.0.2 文案与预览验证（2026-11-07）

- App、当前源码、项目说明和设计文档统一采用中性说明，设计预览和真机「我的」截图重新生成。
- Release 构建和 Lint 通过；安装包内 UTF-8、UTF-16 文字扫描及个人凭据检查通过，发布签名保持一致。
- 已在手机覆盖安装 1.0.2（versionCode 3），账号保留，「我的」页使用“非学校官方客户端”。
- 手机当前为 Android 17 / API 37；旧测试库依赖已移除的系统接口，更新 AndroidX Test Runner 1.7.0、JUnit Extension 1.3.0 和 Espresso 3.7.0 后，目标 profile 仪器测试通过，预览图使用虚构账号。
- 相关测试库修复见 [AndroidX Test 官方说明](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)。
- APK：`deliverables/FZU-GPS-1.0.2.apk`，SHA-256：`1ed589c44ee02d8d094af3ff6322f4d613792ec0b906a2a0c3116037947bd341`。
- [v1.0.2 Release](https://github.com/DriOgon/FZU-GPS/releases/tag/v1.0.2) 已发布；远程附件校验值与本地安装包一致。
- 经用户确认，v1.0.1 Release 的旧 APK 和校验附件已撤下，旧版页面引导下载 1.0.2；本地备份、版本标签与 Git 提交历史保留。

## 后续真机验收清单

连接 Android 手机并在手机上允许 USB 调试后，用独立 `.debug` 包完成虚构数据的界面和存储测试，再安装签名 Release APK。账号密码由用户在手机输入，实际签到由用户手动发起。

每台设备记录型号、Android 版本，并检查：全新安装、登录页小屏和键盘、密码切换、导航、刷新不提交、加密保存后重启、更换账号、清除数据、同签名覆盖升级。协议未经过真实学校服务器确认的部分保持未验证。

如运行存储仪器测试，使用独立 Debug 安装环境：

```powershell
.\tools\build.ps1 -Task DeviceTest
```

Debug 包名为 `com.driogon.fzucheckin.debug`，Release 为 `com.driogon.fzucheckin`。两者账号存储独立，不相互覆盖安装。更换或清除真实账号只通过正式版中的用户操作完成。
