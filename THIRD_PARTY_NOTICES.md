# 第三方依赖说明

本项目自身使用 MIT License。未复制研究参考项目的代码，未引入任何闭源 AAR/SO、Python 运行时、下载聚合器或在线解析服务。

| 依赖 | 固定版本 | 用途 | 许可证 / 来源 |
| --- | --- | --- | --- |
| Kotlin / 标准库 / Compose 编译插件 | 2.1.20 | 原生代码与编译 | Apache-2.0，https://github.com/JetBrains/kotlin |
| kotlinx-coroutines | 1.10.2 | 协程 | Apache-2.0，https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx-serialization-json | 1.8.1 | JSON | Apache-2.0，https://github.com/Kotlin/kotlinx.serialization |
| AndroidX Compose BOM | 2025.04.01 | Compose 版本约束 | Apache-2.0，https://android.googlesource.com/platform/frameworks/support/ |
| Compose Material Icons Extended | 由 Compose BOM 2025.04.01 固定，实际版本见清单 | Google Material 轮廓图标 | Apache-2.0，https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Activity | 1.10.1 | Activity / Compose | Apache-2.0，同上 |
| AndroidX Lifecycle | 2.9.0 | 生命周期 / ViewModel | Apache-2.0，同上 |
| AndroidX Core | 1.16.0 | Android 兼容 API | Apache-2.0，同上 |
| OkHttp / MockWebServer | 4.12.0 | HTTP / 离线网络测试 | Apache-2.0，https://github.com/square/okhttp |
| Okio（OkHttp 传递依赖） | 由 Gradle 解析固定依赖图 | I/O | Apache-2.0，https://github.com/square/okio |
| jsoup | 1.19.1 | HTML DOM 解析 | MIT，https://jsoup.org/license |
| JUnit | 4.13.2 | 测试（不随 APK 打包） | EPL-1.0，https://junit.org/junit4/license.html |
| Hamcrest（JUnit 传递依赖） | 由 Gradle 解析固定依赖图 | 测试断言 | BSD-3-Clause，https://github.com/hamcrest/JavaHamcrest |
| Gradle / Wrapper | 8.11.1 | 构建工具 | Apache-2.0，https://github.com/gradle/gradle |
| Android Gradle Plugin | 8.9.2 | Android 构建工具 | Apache-2.0，https://android.googlesource.com/platform/tools/base/ |

Android SDK 属于开发工具，不打包在本项目中，适用 Google Android SDK 许可条款；JDK 只用于编译，不打包在 APK 中。每阶段最终依赖解析和构建结果记录于 docs/。

AndroidX、OkHttp 等组件的版权归各自贡献者。具体许可证文本以仓库 LICENSE 和 Maven 工件内 META-INF 说明为准。

## 解析后的完整运行时清单

当前固定的 debugRuntimeClasspath 共 69 个工件，其中 1 个为本项目 core，68 个为第三方。Phase 2 沿用 Phase 1 的运行时版本，没有新增运行时依赖。完整坐标、实际版本和工件类型见 [docs/runtime-dependencies.tsv](docs/runtime-dependencies.tsv)；逐项 Maven POM 许可证及来源见 [docs/dependency-licenses.tsv](docs/dependency-licenses.tsv)。该清单覆盖 AndroidX 传递依赖、Kotlin 标准库、Okio、JetBrains annotations、Guava listenablefuture 等，不只列顶层依赖。Phase 2 app 单测新增引用已用于 core 测试的 kotlinx-coroutines-test 1.10.2，许可证 Apache-2.0，不随 APK 打包。

Guava listenablefuture 是并发接口库，不是 Google Analytics。Compose 的 androidx.graphics.path 工件包含开源原生库 `libandroidx.graphics.path.so`，来自 Google 官方 Maven 的 AndroidX；并非来源不明的闭源二进制。项目自身没有 JNI 代码或另行导入的 .so/.aar。

随源码提供 [Apache-2.0 许可证全文](licenses/Apache-2.0.txt) 和 [jsoup MIT 许可证及版权声明](licenses/jsoup-MIT.txt)。

Phase 3 未新增运行时或测试依赖，沿用上述固定版本和完整清单。多 Parser、封面限量读取与图片缩小解码由本项目编写。

0.3.1 未新增 Maven 依赖或打包平台网页脚本，使用 Android 系统提供的 WebView。Node 检查脚本仅用于开发测试，不随 APK 运行。官方网页动态加载资源不是本项目分发的 SDK，适用平台自身规则。

0.4.0 下载器使用既有 OkHttp、Coroutines、AndroidX Core 与 Android 系统 MediaStore/SAF、前台服务和通知 API；未新增运行时 Maven 依赖。新增 MockWebServer 下载与文件事务回归测试仅在开发机执行，不随 APK 打包。

0.5.0 Bilibili 模块独立实现，使用已有 OkHttp 与 kotlinx.serialization 读取平台公开响应，未新增依赖、签名库、解析服务或音视频处理运行时。

## 0.7.0 界面图标

新增 Google 官方 `androidx.compose.material:material-icons-extended` 及其 Android 工件，用于轮廓图标；已有 Material Icons Core 沿用。由现有 Compose BOM 固定版本，完整实际工件/版本见 runtime-dependencies.tsv，对应许可证见 dependency-licenses.tsv。采用官方矢量，不临摹或生成图标，没有图标网络服务。Release 使用现有 R8 配置移除未使用图标；Debug 保留图标库，因此安装包会变大。


