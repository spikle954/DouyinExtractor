# 拾影 · DouyinExtractor

Android 原生、本机提取与下载的公开视频工具。Kotlin + Jetpack Compose，Android 8.0（API 26）起，MIT License。

**当前版本 0.8.5：快手网页辅助优先进入同一作品的官方分享页；补充验证后播放器动态作品字段提取，增加安全验证等待和网页缩小操作。原下载队列、记录、文件保存与 A 版界面保留。** 无广告、统计 SDK、自建解析服务器或第三方解析服务，不导入登录 Cookie。

## 手机使用

覆盖安装 `shiying-0.8.5-debug.apk`，无需卸载此前 debug 版。此前使用 development-signed release 的用户安装对应 release 包；unsigned 包不能直接安装。

1. 粘贴抖音、哔哩哔哩、快手（含极速版）或小红书完整分享文案，或从原 App 的系统分享菜单选择「拾影」。
2. B 站支持 b23.tv 短链、BV/AV 视频链接及纯 BV/av 编号；`?p=2` 指定分 P，多集视频也可在提取结果里选择分 P。
3. 提取完成后选资源并下载。下载过程中可继续解析其他链接，再点击「加入下载队列」。最多 20 个任务，按添加顺序逐个下载；B 站只添加当前分 P。平台实际返回的 MP4 画质和备用线路仍按原规则显示。
4. Android 10+ 默认保存到 `Movies/拾影`，登记到系统视频库，可在文件管理器及相册中查找；也可选择本机文件夹。Android 8/9 使用文件夹选择器，自选目录继续沿用。
5. 当前前台任务完成时显示精简成功弹层；其他排队任务使用完成轻提示，避免打断解析。点击「打开保存文件夹」「播放」或「分享」。首页展示最近两条记录，顶部「下载记录」保留最近 50 条，可搜索标题、作者、视频编号及保存位置。每条记录的更多菜单可查看完整文件信息并操作。重新下载同一作品时会检查旧文件，并提示播放已有文件或仍然下载；B 站不同分 P 分别判断。旧版默认目录仍是 `Download/DouyinExtractor`，不会移动旧文件。

抖音与快手（含极速版）可自动或手动进入当前作品的可见网页提取；在首页「解析选项」关闭自动网页后，使用「用网页继续提取」。出现平台正常验证时可在页面中手动完成，要求登录的内容不支持。抖音仍优先选无水印播放候选，保留原资源。B 站走独立的公开信息和播放请求，不进入抖音 WebView。作者自行加入的字幕、文字和标志不会被处理。

本次快手验证后提取改进：[docs/V085_KUAISHOU_PLAYER.md](docs/V085_KUAISHOU_PLAYER.md)。此前快手网页提取与实测：[docs/V084_KUAISHOU_BROWSER.md](docs/V084_KUAISHOU_BROWSER.md)。此前快手路由修复与实测：[docs/V083_KUAISHOU_SHARE_ROUTE.md](docs/V083_KUAISHOU_SHARE_ROUTE.md)。此前公开页面与诊断改进：[docs/V082_PUBLIC_PAGES.md](docs/V082_PUBLIC_PAGES.md)。极速版适配与实测：[docs/V081_KUAISHOU_LITE.md](docs/V081_KUAISHOU_LITE.md)。新平台支持与实测：[docs/V080_KUAISHOU_XIAOHONGSHU.md](docs/V080_KUAISHOU_XIAOHONGSHU.md)。界面改版：[docs/V070_MATERIAL3_A.md](docs/V070_MATERIAL3_A.md)。本次修正：[docs/V071_VISUAL_FIXES.md](docs/V071_VISUAL_FIXES.md)。视觉验收待新版手机截图：[design-qa.md](design-qa.md)。操作说明：[docs/使用说明.md](docs/使用说明.md)。下载管理：[docs/V060_DOWNLOAD_MANAGEMENT.md](docs/V060_DOWNLOAD_MANAGEMENT.md)。B 站实现与证据：[docs/V050_BILIBILI.md](docs/V050_BILIBILI.md)。保存位置改进：[docs/V051_SAVED_VIDEOS.md](docs/V051_SAVED_VIDEOS.md)。

## 支持范围

- 快手（含快手极速版）：v.kuaishou.com、kuaishou.com/f/、公开 short-video 页及官方手机分享页；从目标作品状态提取完整 MP4。
- 小红书：xhslink.com/xhslink.cn 短链、discovery/item 和 explore 视频笔记；适配手机分享状态和电脑版笔记状态。请保留完整分享链接；xsec_token/xsec_source 仅在内存中用于此次页面提取，规范链接不含访问参数。
- 两个平台暂不支持图文/图集、直播、付费/私密或需要登录的内容，以及分离、分段或加密资源；水印与画质以实际文件为准。
- 抖音：公开普通视频；六个页面解析器与本机网页快照；匹配目标作品后提取媒体；公开无水印候选及原资源。
- 哔哩哔哩：公开普通 UGC、短链、BV/AV、分 P；匿名请求平台允许的完整渐进式 MP4。实际可用画质取决于平台响应，本版不提供账号登录或会员画质。
- B 站的番剧、付费/会员/登录内容、直播、互动视频、DASH 音视频分离、分段、DRM 暂不支持。遇到这些情况会明确失败，不把视频轨或试看片段当作完整成品。
- 最多 20 个任务，单次传输一个文件，单文件最大 2 GB。队列记住各自选定的资源和目录；取消当前任务不清空后续任务，失败项可重新解析，其余可执行任务继续。支持后台通知、进度、文件验证、保存后播放/分享；不支持暂停、断点续传或重启后自动续传。
- 队列在本机保存任务信息，不保存媒体直链。系统杀进程或重启应用后，待办任务会提示重新解析获取新地址；不会自动联网恢复下载。重复提醒只检查最近 50 条保存记录；删除、移动文件或权限变化会影响检查结果。
- 下载先写本机临时文件并检查 MP4 容器，再由 Android 检查视频轨道、尺寸、时长，最后写入所选目录。需要约两份文件的峰值空间；成功后显示实际文件参数。
- MediaStore 使用 pending 状态隐藏未完成文件；SAF 文件保存中可能暂时可见，取消/失败会尝试删除。系统强停、厂商省电、权限撤销或后台时限仍可能导致中断。

## 架构

`ShareTextParser → PlatformVideoService → 平台识别/提取器 → MediaInfo → VideoDownloader → VideoFilePublisher → MediaStore/SAF`

独立 JVM core 保存输入规则、平台请求/解析、目标绑定、媒体模型、推荐规则、下载与文件事务；Compose UI 负责交互，Android 服务负责前台下载和系统保存。

`DouyinUrlResolver / DouyinExtractor / BrowserSnapshotParser` 保留原逻辑。`BilibiliExtractor / BilibiliMapping / BilibiliUrls` 独立处理 B 站，校验 BV/AV 与返回信息一致，再用所选分 P 的 CID 请求播放资源。视频、封面、网页导航各自校验地址；B 站下载只接受 HTTPS bilivideo.com MP4，未知域名不自动放开，必要时选择平台返回的合格备用地址。

网络请求有大小、超时、取消和跳转限制。媒体查询参数在使用时原样保留，不写入诊断或下载记录。B 站原生请求不携带 Cookie/Authorization，不生成签名、不调用第三方解析、不启动网页脚本。

0.7.0 仅新增 Google 官方 Compose Material Icons Extended 图标依赖（Release 按实际使用图标裁剪）；未使用 yt-dlp、youtube-dl、Seal、YTDLnis、NewPipe、Python 运行时或 FFmpeg。网页提取没有 addJavascriptInterface，主页面限于当前抖音或快手作品；正常开启前与关闭后清理网页数据。快手仅读取绑定当前作品的页面状态，并观察网页自身已发出的公开作品响应；不额外发起接口请求，不导出 Cookie。

## 构建与验证

Android Studio 打开本目录。JDK 17、SDK Platform 35、Build Tools 35.0.0；固定 AGP 8.9.2、Gradle 8.11.1、Kotlin 2.1.20、Compose BOM 2025.04.01。

```powershell
.\gradlew.bat assembleDebug assembleRelease test lintDebug
```

macOS/Linux 使用 `chmod +x gradlew` 和 `./gradlew`。业务测试离线，合成媒体只用于测试，不进入生产解析逻辑。可选脚本检查：`node tools/check-browser-capture.mjs` 和 `node tools/check-kuaishou-browser.mjs`（合成 Node 检查，不能替代 Android WebView 测试）。

保留保存反馈时机、队列顺序、取消与中断交接、记录搜索及重复检查的离线回归；提取与媒体传输规则沿用已交付版本。B 站生产下载和播放实测结果：[docs/v050-live-download.json](docs/v050-live-download.json)。你提供的快手和小红书样本已用实际生产解析器与下载器完成 MP4 下载，并检查音视频轨道，证据：[docs/v080-live-download.json](docs/v080-live-download.json)。0.8.4 的原生与网页实测保留于 [docs/v084-live-download.json](docs/v084-live-download.json)。本次使用新样本的实际播放器动态数据，单独通过新增路径提取 6 个目标资源，并下载第一条提供的完整 720 × 1280 AVC/AAC MP4；证据：[docs/v085-live-browser.json](docs/v085-live-browser.json)。此处浏览器是独立桌面 Chrome，尚未连接 Android 设备验证本机网页流程。当前编译与回归结果：[docs/v085-validation.json](docs/v085-validation.json)。已有 0.7.0 首页、输入法、记录和解析结果真机截图，用户确认历史视频能播放；0.7.1 本地解码预览与固定按钮尚待手机复拍。队列后台通知、系统中断恢复及无障碍仍需手机复测。

## 签名与隐私

debug 包名 `io.github.douyinextractor.debug`；release 包名 `io.github.douyinextractor`；versionCode 20，沿用开发证书。可通过 DEBUG_STORE_FILE 设置原开发 keystore；正式 release 用 RELEASE_STORE_FILE、RELEASE_STORE_PASSWORD、RELEASE_KEY_ALIAS、RELEASE_KEY_PASSWORD。私钥不在源码中。

平台可以看到客户端请求和出口 IP；本机提取不意味着对平台隐藏网络活动。WebView 执行平台脚本，资源过滤不能保证阻止全部平台统计。剪贴板仅点击粘贴时读取；诊断由用户主动复制，分享文案仍需自行检查。

隐私：[PRIVACY.md](PRIVACY.md)。依赖：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。许可：[LICENSE](LICENSE)。历史：[0.4.1 无水印选择](docs/V041_WATERMARK.md)、[Phase 4 下载](docs/PHASE_4_DOWNLOAD.md)、[0.3.4 网页衔接](docs/V034_FIX.md)。
