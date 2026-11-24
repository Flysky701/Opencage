# 安全说明

## 隔离与网络边界

Open Cage 无需 Root。来宾进程和宿主在同一 Android 应用沙箱内运行，共用宿主 UID。进程管理、文件路径隔离与服务路由由容器实现，保护范围限于该 Android 沙箱，无法提供虚拟机或内核级强隔离。

来宾的默认连接和系统 DNS 使用启动时绑定的 VPN。VPN 断开或切换时，Open Cage 会结束对应来宾进程。建议在手机系统设置中同时开启「始终开启 VPN」和「屏蔽未使用 VPN 的连接」，并确认 VPN 覆盖 Open Cage。应用自行选网时，系统级约束取决于设备的 VPN 配置。出口绑定会经 VPN 向公网 IP 服务核对 IPv4，并在对应操作中将已核对的地址提交给 ipwho.is 查询地区和时区。

RC3.18 将隐私隔离门禁和子进程预加载扩展至 Android 14–16（API 34–36）ARM64。已移除单机型 `libc` 样本文件及指纹校验头文件。VPN 接口过滤只注册 `getifaddrs` Hook。VPN 过滤启用时，Hook 确认 `getifaddrs` 和 `freeifaddrs` 的原始符号来自同一 `libc.so`，且前序函数就是原始 `getifaddrs`，才会过滤隧道接口。只保留部分接口时，Hook 逐个分配节点，复制名称和支持的套接字地址，再用原始 `freeifaddrs` 释放原链表。全部过滤时释放原链表并返回空链表。若无法确认原始函数，保留原链表。若分配失败或接口数据无法复制，释放原链表和已分配节点，将输出置空并返回错误。`FileProvider` 校验 Authority 和路径，将文件共享限制在配置允许的范围内。

自检只覆盖本轮探针实际测得的项目。不同系统、VPN 配置和 WebView 上的实际来宾流量及行为须分别验证。

## 真机验证边界

- **构建、审计与真机。** RC3.18（`1.0.0-rc.3.18`）已完成从源码独立构建（干净构建会在本地生成随机 `probe.p12` 用于签名自检探针，故非字节级完全一致构建）、纯 Java 规则单测、符号审计和安全审计。Android 14、15、16 ARM64 跨机型适配与新应用 ID `io.github.flysky701.opencage` 均待真机实测。RC3.15、RC3.16 的旧包名真机记录只覆盖当时测试的设备和配置。[应用构建配置](app/build.gradle)
- **Google Play 结算。** 真实 Google Play 扣款和收据核销尚未验证。
- **Claude 登录。** Claude 的 Google 一键登录完整性校验仍在单独推进。

## 构建输入校验

- **microG Services。** `rebuild.py` 按 `bundle.lock.json` 中的 SHA-256 核对原始 APK，校验通过后保留其原签名并复制到构建资源目录。本地 `--microg` 输入也经过同一校验。需要安装内置 APK 时，`BundledApps` 按随包清单复核复制后的摘要、包名和版本号，并在安装后复核空间路径、包名、版本号和已安装文件的 SHA-256。已安装的 microG Services 直接保留。[APK 锁定信息](bundle.lock.json) · [随包清单](app/src/main/assets/bundle/manifest.json)
- **ByteHook 与 ShadowHook。** 默认构建使用缓存或从 Maven Central 下载两个官方 AAR，按 `PRIVACY_AARS` 核对 SHA-256，再提取并检查 ARM64 ELF 库。使用 `--privacy-deps` 时，脚本按 `PRIVACY_LIBS` 校验本地 `.so` 的 SHA-256 与 ARM64 ELF 架构，缺少本地库时回退至官方 AAR。[构建脚本](rebuild.py) · [第三方声明](THIRD-PARTY-NOTICES.md)
- **自检探针。** `preflight.apk` 从仓库源码构建并签名后，脚本将随包文件的 SHA-256 写入 `BuildInfo.PROBE_SHA`。安装或重装前，`PreflightRunner` 复核从应用资源复制出的 APK 的 SHA-256、包名和版本号。安装后以及已记录的资源摘要与构建摘要匹配时，`BundledApps.installed` 都会查询安装记录并核对 `sourceDir` 是否指向当前空间预期的 `base.apk` 路径。`PreflightRunner` 随后通过 `matchesInstalledProbe` 核对包名、版本号、文件是否存在和 SHA-256。缓存校验失败时尝试重装。安装后的校验失败时报告错误。[探针安装逻辑](app/src/main/java/io/github/flysky701/opencage/PreflightRunner.java)

## APK 安装与 Binder 调用

- **APK 证书收集。** `PackageParserCompat` 在 `BuildCompat.isPie()` 分支统一调用 `collectCertificates(p, false)`，以 `skipVerify = false` 执行 APK 内容签名校验。该分支覆盖 Android 9（API 28）及以上，包括 Android 14–16，也识别 `SDK_INT = 27` 且 `PREVIEW_SDK_INT = 1` 的预览版。`BPackageManagerService.parseApkPackage` 检查异常链，遇到 `SecurityException` 或 `PackageParserException` 时直接拒绝安装。只有旧解析器未返回解析器实例或包对象，或异常被判定为反射、链接不可用（`legacyUnavailable`）时，Android 13（API 33）及以上才尝试 `PackageParserFix`。兼容路径从系统包管理器读取 APK 签名，并通过 `hasValidSignatures` 要求签名数组有条目且每项均非空。
- **安装与覆盖安装。** `BPackageManagerService.installPackageAsUserLocked` 从新 APK 提取签名，并用 `hasValidSignatures` 检查签名数组。不符合要求时返回 `No valid APK certificates found.`。容器包表已有同包名应用时，`signaturesMatch` 要求新旧签名集合一致。签名不匹配时立即返回 `Signature mismatch for existing package` 安装错误。校验通过后，更新流程沿用已有包的应用 ID 和用户状态。
- **Binder 调用 UID（已知局限）。** `BinderHook` 当前保留上游 BlackBox 的 JNI Hook 实现（调用 `NativeCore.getCallingUid` 映射进程 UID），尚未针对 Android 8–16 的 `@CriticalNative` 路径单独实现零 JNI 原生通道，该项保留为后续真机验证后再优化的已知局限。

## 私下报告安全问题

如仓库已启用私密漏洞报告，请在 GitHub 仓库页面打开 **Security → Advisories → Report a vulnerability**。如果入口不可用，请通过仓库页面联系维护者，请求私下沟通渠道。公开议题只用于请求联系方式，漏洞细节请留在私密渠道。报告可包含受影响的版本、应用 ID、复现步骤、预期行为和脱敏后的实际结果。提交前请移除账号、令牌及其他个人数据。
