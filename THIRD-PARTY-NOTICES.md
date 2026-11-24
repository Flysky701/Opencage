# Open Cage 1.0.0-rc.3.18

Open Cage 以 GNU GPL v3.0 分发修改后的完整对应源码，并保留各组件的上游许可和著作权声明。
[仓库 GPL-3.0 正文](LICENSE) · [安装包 GPL-3.0 正文](app/src/main/assets/bundle/GPL-3.0.txt) · [安装包 Apache-2.0 正文](app/src/main/assets/bundle/APACHE-2.0.txt) · [应用内声明](app/src/main/assets/bundle/NOTICE.txt)

## 本仓库保留的源码与资源

| 组件 | 许可、来源和用途 |
| --- | --- |
| Claude-Cage | MIT。原始首页框架与图标来自 Claude-Cage。Copyright (c) 2026 Sylvain Zimmer。Copyright (c) 2026 Claude Cage contributors。[CAGE-MIT.txt](app/src/main/assets/bundle/CAGE-MIT.txt) |
| BlackBoxReborn v2.1.0 | 锁定提交 `fd0640919c7903a167abf0215ee68ebeaea53485`。保留上游 `LICENSE` 的 Apache-2.0 许可证及上游 README 的 GPL-3.0 声明。Open Cage 对应源码包含对宿主界面、来宾应用安装、Android 服务路由和账号集成的修改，按 GPL-3.0 分发。[上游仓库](https://github.com/fdavids77/BlackBoxReborn) · [来源锁定](sources.lock.json) · [上游 Apache-2.0 原文](licenses/BlackBoxReborn-LICENSE.txt) |
| BlackReflection | 随 BlackBoxReborn 同一提交保留，沿用该源码树的许可声明。`black-reflection/` 提供运行时反射支持，`compiler/` 提供构建期注解处理器。[BlackBoxReborn](https://github.com/fdavids77/BlackBoxReborn) |
| Dobby | Apache-2.0（主源码）。构建从 `dobby/` 中锁定于 `b0176de574104726bb68dff3b77ee666300fc338` 的源码编译 ARM64 静态库 `libdobby.a`，编译时包含 TINYSTL 头文件。该静态库链接进 `libblackbox.so` 随最终 APK 分发，因此 TINYSTL 的完整 BSD-2-Clause 声明原文已收录于安装包内置的 [`app/src/main/assets/bundle/NOTICE.txt`](app/src/main/assets/bundle/NOTICE.txt)。保留树已移除未用于 Android 构建的 Darwin APSL 2.0 文件与 `.vscode` 元数据，并保留 V8 `semaphore.cc`（Copyright 2013 the V8 project authors，BSD-3-Clause）。[上游仓库](https://github.com/jmpews/Dobby) · [来源锁定](sources.lock.json) · [Dobby 原始许可](dobby/LICENSE) |
| SandHook (`ElfImg`) | Apache-2.0（来自上游 `BlackBoxReborn` / SandHook）。`Bcore/src/main/cpp/Utils/elf_util.h`、`elf_util.cpp` 提供运行时 ELF 符号解析支持。[上游仓库](https://github.com/fdavids77/BlackBoxReborn) |
| xDL | MIT。`Bcore` 内置源码的声明为 Copyright (c) 2020-2024 HexHacking Team。[上游仓库](https://github.com/hexhacking/xDL) · [xDL-MIT.txt](app/src/main/assets/bundle/xDL-MIT.txt) |

## 构建引入的组件

| 组件 | 许可、来源和用途 |
| --- | --- |
| microG Services v0.3.18.252432 | Apache-2.0。Copyright the microG Project Team and contributors。构建按 `bundle.lock.json` 选定 microG Services APK，从缓存、上游下载或 `--microg` 指定文件取得原始 APK。SHA-256 校验通过后，将 `com.google.android.gms-252432036.apk` 原样复制至 `assets/bundle/`，保留上游签名。同一锁定文件中的 microG Companion 条目仅供参考。[上游发布页](https://github.com/microg/GmsCore/releases/tag/v0.3.18.252432) · [APK 锁定信息](bundle.lock.json) |
| ByteHook 1.1.2 | MIT。Copyright (c) 2020-2026 ByteDance, Inc.。提供原生 PLT Hook。官方 `com.bytedance:bytehook:1.1.2` AAR 提供 `libbytehook.so`。[上游版本](https://github.com/bytedance/bhook/tree/v1.1.2) · [完整声明](privacy-native-src/third_party/NOTICE.txt) |
| ShadowHook 2.0.1 | MIT。Copyright (c) 2021-2026 ByteDance Inc.。提供原生 Inline Hook。官方 `com.bytedance.android:shadowhook:2.0.1` AAR 提供 `libshadowhook.so` 和 `libshadowhook_nothing.so`。[上游版本](https://github.com/bytedance/android-inline-hook/tree/v2.0.1) · [完整声明](privacy-native-src/third_party/NOTICE.txt) |
| FreeReflection 3.2.2 | MIT。Copyright (c) 2018 weishu。`Bcore` 通过 `com.github.tiann:FreeReflection:3.2.2` 引入，用于隐藏 API 兼容。[上游仓库](https://github.com/tiann/FreeReflection) · [FreeReflection-MIT.txt](app/src/main/assets/bundle/FreeReflection-MIT.txt) |

构建按 `bundle.lock.json` 校验 microG APK 的 SHA-256。默认构建或本地库缺失时，`rebuild.py` 使用缓存或从 Maven Central 下载两个官方 AAR，按 `PRIVACY_AARS` 校验 SHA-256，再提取并检查 ARM64 ELF 库。通过 `--privacy-deps` 提供的本地库按 `PRIVACY_LIBS` 校验 SHA-256，并检查 ARM64 ELF 架构。[APK 锁定信息](bundle.lock.json) · [构建脚本](rebuild.py)

| 输入 | SHA-256 |
| --- | --- |
| `com.google.android.gms-252432036.apk` | `1094fa60ace2367ac52bd08d1bd4f33fa8144ba930e355121b28234b8ebbf382` |
| `bytehook-1.1.2.aar` | `b3c85fec26e9f41fd3608851ac3fe412573ef4ce6ef83911b92d610d3bab7215` |
| `shadowhook-2.0.1.aar` | `2cad01ff4d59542958775e19f281981a7e84b39670b5fae75905b8511e10c7cd` |
| `libbytehook.so` | `52037a6086f5b6c4ab588393b917d92a9b49beedec9813fd08f92666d9bf0433` |
| `libshadowhook.so` | `fc84287eac46e3bade2f7e07e3c9efdca005822e21191d6db5b8cb222d53e8a0` |
| `libshadowhook_nothing.so` | `f2cc6a6f94d7361be915daafff1d25b7b3af34972e7308ac540982cfd608bd32` |

ByteHook 随附 BSD `queue.h`（Copyright (c) 1991, 1993 The Regents of the University of California）与 BSD `linux_syscall_support.h`（Copyright (c) 2005-2011 Google Inc.）。ShadowHook 随附 xDL 2.4.0（MIT，Copyright (c) 2020-2025 HexHacking Team）。构建将这些完整声明复制至 `assets/cage-privacy/THIRD-PARTY-NOTICES.txt`。[原始声明](privacy-native-src/third_party/NOTICE.txt)

## 保留的上游配置引用

HiddenApiBypass（Apache-2.0，LSPosed）的版本引用 `hiddenApiBypass = '4.3'` 保留在根 `build.gradle` 中，当前 Release 依赖声明未引用该属性。运行时由 `Bcore` 的 `hidden_api.cpp` 使用 `SandHook::ElfImg` 查找 ART 符号，原生路径失败时回退到 FreeReflection。[上游项目及许可](https://github.com/LSPosed/AndroidHiddenApiBypass) · [构建配置](build.gradle)

## 专有软件

本仓库和 Open Cage 安装包均不分发 Google Play、Claude 等专有 APK。需要使用时，由设备所有者自行获取并安装或导入。相关商标归各自权利人所有。
