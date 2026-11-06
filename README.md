# 福大签到助手 · FZU-GPS

Android 原生晚点名工具，Kotlin + Jetpack Compose，Android 8.0 及以上。界面参考 [福 uu](https://fzuhelper.w2fzu.com/) 的蓝白校园工具风格，使用独立名称与图标；不是学校或福 uu 官方客户端。

![设计预览](docs/previews/overview.png)

## 使用

1. 安装开发者提供的签名 APK，不需要在手机安装 Python，也不需要 Android Studio。
2. 输入自己的统一身份认证账号密码。凭据使用 Android Keystore 管理的密钥加密后保存在本机，不上传到额外服务器。
3. 打开 App 查看当天计划，在允许的时间点击「签到」。打开、登录和刷新均不会自动提交。
4. 以查询确认的结果为准。「处理中」「结果待确认」均不等于成功，请先刷新核实。

遇到验证码会停止，不提供绕过或自动识别。签到要求以学校规定为准。当前版本使用原脚本配置坐标，不读取设备 GPS；校内/校外方式由服务器决定，校外方式地址为空时不能提交。客户端不保证任何账号或地点均能签到成功。

「我的」可更换账号、清除本机数据和查看说明。每台手机只保存一个账号，Cookie 和 Token 仅存于内存。应用不申请定位、通知或后台执行权限，不包含统计上报。

## 工程

```text
android-app/   Android 工程，含 Gradle Wrapper 和离线协议/界面测试
reference/     去掉个人账号密码的 Python 参考版
docs/          设计规范、预览与验证说明
tools/         构建、设计预览、协议测试数据和发布前检查脚本
```

## 构建

固定工具版本：JDK 17、Gradle 8.13、Android Gradle Plugin 8.11.1、Kotlin 2.1.21、compile/target SDK 36、Build Tools 35.0.0。首次构建需联网获取依赖。依赖版本固定在 Gradle 文件，Wrapper 配置校验 Gradle 分发包的 SHA-256。

可以用 Android Studio 打开 `android-app`，或安装 JDK 17 和 Android SDK、配置 `JAVA_HOME` 与 `ANDROID_HOME` 后使用命令行。工具也可放在项目内 `.tools/jdk17` 和 `.tools/sdk`；构建脚本优先使用这些位置，不改系统 Java。

Windows PowerShell：

```powershell
.\tools\build.ps1 -Task Check    # Debug APK、单元/UI 模拟测试、Lint
.\tools\build.ps1 -Task Debug    # 只构建 Debug APK
.\tools\new-signing-key.ps1      # 首次生成自己的本地发布签名；已有签名时拒绝覆盖
.\tools\build.ps1 -Task Release  # 用本地签名构建 Release APK 并运行 Lint
```

其他平台：

```sh
cd android-app
./gradlew assembleDebug testDebugUnitTest lintDebug
```

APK 输出在 `android-app/app/build/outputs/apk/`。Release 签名配置为项目根目录 `.signing/release.properties`，内容包含 `storeFile`（相对 `.signing`）、`storePassword`、`keyAlias`、`keyPassword`。请妥善私下备份整个 `.signing`；更新安装必须沿用同一应用标识和签名。不要分享或上传私钥、签名配置、账号数据或排错页面。

如果只有系统代理可联网，需在个人 Gradle 配置中设置 Java 的 HTTP/HTTPS 代理；不要把本机代理配置提交到项目。

## 验证与发布

测试使用虚构账号、MockWebServer 和 Robolectric，不向学校提交签到。Python/Kotlin 加密对照数据可用 `python tools/generate_vectors.py` 生成（需 pycryptodome）；设计预览可用 `python tools/render_design.py` 生成（需 Pillow 和中文字体）。预览是设计稿，实际模拟界面截图输出到测试构建目录。

提交前运行 `python tools/check_secrets.py`。原始脚本、个人配置、工具缓存、签名和 APK 全部排除 Git；只提交脱敏参考版。实测结果与尚未完成的真机验收见 [验证记录](docs/verification.md)。

学校接口和页面可能变化。登录失败请先检查凭据；验证码请稍后重试；提交超时请刷新核实。本项目不实现后台自动重试提交。
