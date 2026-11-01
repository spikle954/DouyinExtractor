# Phase 1 验收记录

日期：2026-10-03，Asia/Shanghai。

## 已实现

- Kotlin / Compose 项目，Android 8.0+，app 与纯 JVM core 模块分离。
- 中文首页、简洁浅/深色主题、粘贴、编辑、处理按钮与本地隐私说明。
- Manifest 注册 ACTION_SEND + text/plain；Activity 冷启动与 onNewIntent 热启动共享同一 ViewModel 入口。
- 初始 Intent 已处理标记放在 ViewModel，配置变更重建 Activity 时不重复消费同一份初始分享。
- 进入处理入口明确显示开发状态；没有固定视频地址或虚构 MediaInfo。
- 系统备份关闭；显式排除 Android 12+ 云备份和设备迁移内容。
- Gradle Wrapper 与下载 SHA-256 固定，MIT、依赖清单、隐私政策、中文说明与调查报告。

## 编译与检查

工具链：Temurin JDK 17.0.20.1、Gradle 8.11.1、AGP 8.9.2、Android Platform 35、Build Tools 35.0.0。官方 JDK/Gradle SHA-256 与 Android 仓库 SDK SHA-1 均校验一致。

执行：

```text
assembleDebug assembleRelease test lintDebug
```

源码编译、debug 打包、release R8 压缩与打包通过。最终日志随交付整理为 phase1-build.txt。

测试任务实际运行情况：app debug/release 与 core 的单元测试均为 NO-SOURCE，目前用例数为 **0**。这是本阶段还没有业务用例，不是“所有业务测试通过”。Phase 2 起添加用户要求的离线输入、URL、redirect、JSON fixture 和格式选择测试。

Lint：修复了数据迁移排除规则提示；剩余依赖版本提示属于固定兼容版本的已知提示。没有通过关闭 Lint 来隐藏问题，最终错误和警告数见同目录 lint-results-debug.txt。

APK 用 Android Build Tools 的 apksigner 验证开发签名，用 aapt 检查 minSdk=26、targetSdk=35、Manifest 分享注册和权限。仅有 INTERNET 及 AndroidX 自身的 signature 级动态接收器权限，没有存储、联系人、位置、相机等敏感权限。

## 构建问题及修复

首次自定义 debug keystore 路径不存在；改回默认后，受限 Windows 环境在 Android 工具的 ReadWriteProcessLock 上出现 AccessDeniedException。使用本地生成的独立开发密钥和可选 DEBUG_STORE_FILE 配置后通过。普通 Android Studio 保留默认行为；没有放宽工作目录 ACL、关闭 TLS 检查或提交私钥。

构建提示 AndroidX graphics.path 原生库未剥离调试符号，该库按官方依赖原样打包；不影响本次编译，未安装 NDK。

## 尚未验收

- adb 未发现已连接设备。因此未执行安装、启动、系统分享面板、旋转、屏幕阅读器或 Android 8/15/16 运行验证。
- 隔离桌面 Edge 浏览器因 GPU 子进程失败退出，未获得 DOM。不能以此声称 Android WebView 能解析真实页面。
- Phase 2–6 尚未实现；当前不是完整视频下载 App。

## 构建产物与签名

debug APK 用本次开发密钥签名，可用于安装测试。release-unsigned APK 是 R8 后待正式签名的构建产物。额外提供的 release-development-signed APK 使用同一开发密钥，仅供安装测试，不是正式发行身份。源码包不含任何密钥、SDK、Gradle 缓存或机器路径。
