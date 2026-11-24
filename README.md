# Open Cage 1.0.0-rc.3.18

Open Cage 是一个从公开源码独立构建的 Android 本机隔离容器，面向 ARM64 设备，无需 Root。当前版本为 `1.0.0-rc.3.18`，`versionCode` 为 `10020`，应用 ID 为 `io.github.flysky701.opencage`。完整对应源码按 GNU GPL v3.0 分发。[项目许可证](LICENSE) · [第三方声明](THIRD-PARTY-NOTICES.md)

## 分发与安全说明

**Open Cage 是独立开源项目，与 Anthropic、Google 或任何闭源商业虚拟容器软件均无隶属、合作或背书关系。** 相关商标归各自权利人所有。

**本仓库以源码形式提供项目，仓库和构建出的安装包均不分发 Google Play、Claude 等专有 APK。** 这些应用由设备所有者自行获取并安装或导入。构建用到的开源组件 microG Services、ByteHook 和 ShadowHook 均有锁定的版本与 SHA-256。[microG 锁定信息](bundle.lock.json) · [原生组件构建脚本](rebuild.py)

受底层容器的历史实现和 Android Framework 兼容性限制，应用沿用 `targetSdk = 28`，通过 ByteHook、ShadowHook、Dobby 等原生 PLT／Inline Hook 和进程、文件隔离机制实现兼容。部分系统安全管家或 Play Protect 可能将这些行为误判为风险。可使用本仓库提供的完整对应源码和构建步骤自行审计、编译。来宾和宿主共用 Android 应用沙箱与 UID，隔离能力限于该边界。[安全边界与反馈方式](SECURITY.md)

## 截至 RC3.18 的功能

- **空间应用管理。** 从设备已安装应用或文件导入 APK，支持导入同版本、原签名的完整 Claude ARM64 分包。可按应用名称或包名搜索。移除操作只针对空间内的应用，设备上安装的原应用保留。
- **启动检查与缓存。** 启动空间应用前检查权限与 VPN 状态。检查缓存支持 0–24 小时，默认 12 小时，设为 0 即关闭缓存。VPN 断线保护独立于缓存，始终生效。
- **VPN 与出口 IPv4 绑定。** 来宾默认连接和系统 DNS 绑定到当前 VPN，VPN 断开或切换会结束来宾进程。出口绑定通过当前 VPN 使用 icanhazip.com 或 ipify 核对公网 IPv4，并向 ipwho.is 查询已核对地址的地区和时区。可以手动覆盖空间语言和时区。
- **后台空间自检。** `preflight` 探针在后台透明窗口中运行，Open Cage 首页及其检查界面即时显示检查状态和结果。新一轮自检只接受与本次会话匹配的完整报告。
- **microG 与 Play 兼容。** 「一键配置 microG」准备构建时已锁定和校验、保留上游签名的 microG Services，开启设备注册和 Google Play 结算。经签名校验的 Google Play 可发现同一空间内的 Google 账号，并按应用请求拉起结算弹窗。
- **RC3.16 精简与压缩。** 移除 Release 运行依赖中的 AndroidX、Material Components、toml4j 和 Gson，启用 Release R8 代码压缩和资源压缩。
- **RC3.17 WebView 时区与自检会话。** `WebViewTimezone` 实现了面向 Android 13 及以上现代 Chromium WebView（156 至 158 及后续采用相同结构的版本）的时区结构化定位与渲染器预热。自检采用串行会话，开始前停止上轮探针，探针销毁时清理回调和 WebView。
- **RC3.18 Android 14–16（API 34–36）ARM64 多机型适配。** 隐私隔离门禁与子进程预加载扩展至 Android 14 及以上的 ARM64 设备。
- **RC3.18 VPN 接口过滤。** 移除了单机型 `libc` 样本文件及指纹校验头文件。`getifaddrs` Hook 确认可调用同一 `libc.so` 中的原始函数后过滤隧道接口。需要保留部分接口时，Hook 为保留的接口重新分配并复制节点及其数据，再调用原始 `freeifaddrs` 释放原链表。分配或复制失败时返回错误。
- **RC3.18 容器与安全加固。** 修复 `UnixFileSystemHook` 的 JNI 类型匹配和 `JniHook` 的偏移校准边界，加固 `FileProvider` 的路径与 Authority 校验。`PreflightRunner` 在安装探针前核验从资源复制的 APK 的 SHA-256、包名与版本号。安装后和缓存命中时均核验已安装探针的包名、版本号、空间 `base.apk` 路径和 SHA-256。清理多余的权限绕过、调试内存读接口和历史测试特例。[安全说明](SECURITY.md)
- **APK 签名校验。** `PackageParserCompat` 在 `BuildCompat.isPie()` 分支统一调用 `collectCertificates(p, false)`，在 Android 9 及以上（含 Android 14–16）执行 APK 内容签名校验。`BPackageManagerService.parseApkPackage` 遇到 `SecurityException` 或 `PackageParserException` 时拒绝安装。Android 13 及以上仅在旧解析器未返回结果或其反射、链接不可用时尝试 `PackageParserFix`，并检查兼容路径返回的签名。安装时要求新 APK 提供有效签名。覆盖同包名应用时，`signaturesMatch` 比对新旧签名，不匹配则返回 `Signature mismatch for existing package` 安装错误。[安全与安装边界](SECURITY.md#apk-安装与-binder-调用)

Claude-Cage 的定位是桌面端环境与手机远程遥控端。Open Cage 专注于在 Android 设备本机运行免 Root 的独立隔离空间。Claude 支持使用邮箱登录，通过官方邮件中的 Magic Link 或在登录页输入官方验证码继续。

## 真机验证边界

- **构建、审计与真机。** RC3.18（`1.0.0-rc.3.18`）已完成从源码独立构建（干净构建会在本地生成随机 `probe.p12` 用于签名自检探针，故非字节级完全一致构建）、纯 Java 规则单测、符号审计和安全审计。Android 14、15、16 ARM64 跨机型适配与新应用 ID `io.github.flysky701.opencage` 均待真机实测。RC3.15、RC3.16 的旧包名真机记录只覆盖当时测试的设备和配置。[应用构建配置](app/build.gradle)
- **Google Play 结算。** 真实 Google Play 扣款和收据核销尚未验证。
- **Claude 登录。** Claude 的 Google 一键登录完整性校验仍在单独推进。

## 从源码构建

准备 Python 3.12+、JDK 17、Android SDK Platform 36、Build Tools 36.1.0、NDK 27.0.12077973 和 SDK CMake 3.31.6。在源码根目录运行以下命令，将占位符换成工具安装目录。

```text
python rebuild.py --sdk <Android-SDK> --jdk <JDK-17>
```

- `--microg <APK>` 使用提前下载的锁定版本 microG Services APK，脚本仍会核对 SHA-256。
- `--privacy-deps <目录>` 使用本地的 `libbytehook.so`、`libshadowhook.so` 和 `libshadowhook_nothing.so`。脚本按 `rebuild.py` 的 `PRIVACY_LIBS` 核对本地库的 SHA-256，并检查 ARM64 ELF 架构。默认构建或本地库缺失时，脚本校验官方 AAR 的 SHA-256 后提取并检查 ARM64 库。[第三方声明与输入摘要](THIRD-PARTY-NOTICES.md)
- `--offline` 让 Gradle 使用本地依赖缓存。若要让整个构建在离线环境运行，还需提供相应的 microG APK 和官方 AAR 缓存，或使用前面两个选项提供本地输入。

未签名的 Release APK 位于 `app/build/outputs/apk/release/`。安装前须签名，更新同一应用 ID 时须沿用原签名。

## 运行纯 Java 规则测试

`verification/CheckPolicyTest.java` 无需 Android SDK 或设备，覆盖 `CheckPolicy.allowsLaunch` 的权限和 VPN 状态组合，以及 `CheckPolicy.reusable` 的 0–24 小时缓存边界、环境变化和设备重启。以下为在仓库根目录运行的 PowerShell 示例。

```powershell
$classes = Join-Path ([System.IO.Path]::GetTempPath()) ('open-cage-check-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $classes | Out-Null
javac -encoding UTF-8 -d $classes `
  app/src/main/java/io/github/flysky701/opencage/CheckPolicy.java `
  verification/CheckPolicyTest.java
java -cp $classes io.github.flysky701.opencage.CheckPolicyTest
```

编译时使用与 Java 包路径对应的 `CheckPolicy.java` 文件，让测试的 `package` 声明和 `java` 命令的全限定类名保持一致。[测试源码](verification/CheckPolicyTest.java)
