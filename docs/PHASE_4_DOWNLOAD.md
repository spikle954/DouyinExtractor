# 0.4.0 · Phase 4 下载与文件保存

## 使用结果

用户确认 0.3.4 的两个随机分享链接可以自动提取目标视频资源，本版接上「选择资源 → 下载视频 → 保存到手机」。版本号 0.4.0，versionCode 8，沿用开发签名；未增加运行时依赖。

支持单任务后台下载、通知栏/应用内进度、取消、目录记忆、完成后播放和分享。百分比、总大小、速度、预计剩余时间按实际传输计算，服务端不提供长度时不虚构总大小。用户主动点下载才传输文件，网页提取不会自动保存。

## 实现与失败处理

- core/download/VideoDownloader：既有 OkHttp 流式读取；匿名、无 Cookie/Authorization；逐跳检查 HTTPS 和平台媒体域名，最多五次重定向。媒体签名参数保留用于请求，不打印、不写任务记录；拒绝不受支持的地址。
- HTTP 401/403/404/410 提示地址过期或拒绝访问；其他非 200（包括未请求的 206）作为错误。拒绝 HTML/JSON/HLS/音频响应；检查已知长度、2 GB 上限，以及 MP4 的 ftyp/moov/mdat 边界。错误网页不会被改名成 MP4 当作成功。
- 私有 staging 文件落在本应用缓存目录。Android MediaMetadataRetriever 继续检查视频轨道、正的尺寸与时长。检查通过后才创建公共保存目标；不声明已经完整解码所有帧或保证播放器兼容。
- VideoFilePublisher 独立处理创建、打开、复制、关闭、长度检查、提交/回滚。复制可取消；提交及成功记录不可被半途取消。提交边界之后到来的取消保留已完成文件。失败/取消尝试删除本任务创建的目标，不覆盖用户已有文件。
- MediaStore/SAF 适配在 app/download/VideoStorage 中。默认 Download/DouyinExtractor 使用 IS_PENDING；成功才公开，失败则删除。SAF 目标由提供程序管理，复制中可能暂时可见。清理失败保留本机标记，下次打开重试；已提交的 MediaStore 文件和记录为完成的文件不被恢复清理误删。
- DownloadRuntime 保存最近任务状态及清理标记，不保存媒体地址。新解析或新分享不取消正在下载的任务；重复点击不会启动多个任务。被系统杀进程后下次打开提示中断，清理残留，不悄悄重启网络下载。
- VideoDownloadService 为非导出 dataSync 前台服务。通知可取消/返回应用，完成后可从原生界面播放/分享。部分唤醒锁限时持有并在结束释放；下载请求有超时，整体任务上限 40 分钟；处理 Android 的服务超时回调。

## Android 平台依据

Android 10+ 通过 MediaStore.Downloads 保存本应用创建的文件不需要广泛存储权限；本版使用 RELATIVE_PATH 和 IS_PENDING。依据：[Android 共享媒体存储文档](https://developer.android.com/training/data-storage/shared/media)。

自选目录使用系统 ACTION_OPEN_DOCUMENT_TREE 和持久 URI 授权；本版 Android 8/9 使用这条路径，Android 10+ 也可选择。请求文件选择器仅显示本机内容；具体提供程序的行为由系统及提供程序控制。依据：[Android 文档和文件访问](https://developer.android.com/training/data-storage/shared/documents-files)。

用户在前台点下载才启动 dataSync 服务，声明 FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC。Android 13+ 提供通知授权选择，拒绝后仍能在 App 查看状态。相关依据：[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。Android 15 对 dataSync 服务施加时间限制，本版实现 onTimeout 并停止任务：[服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)。

## 本次真实跳转调查

针对用户已成功提取的作品 7691299102264413480，使用隔离 Chrome、正常 TLS/浏览器沙箱、无导入登录态打开官方视频页。页面跳至同作品 m.douyin.com 分享页，_ROUTER_DATA 中确有目标视频信息。

初次调用新下载器时，官方 aweme.snssdk.com 播放入口重定向到 HTTPS 的 v5-hl-mly-ov.zjcdn.com；旧媒体域名列表未包含它，因此正确返回 UNSAFE_URL 而非下载成功。依据这次平台自己的真实跳转，将 zjcdn.com 加入媒体域名范围，并新增边界测试，仍拒绝 HTTP、用户名、非常规端口、相似假域名。没有扩大分享 URL 或 WebView 导航范围，没有改写水印地址、签名或访问权限。

本次最终文件下载结果见 [v040-live-download.json](v040-live-download.json)。桌面 JVM 下载验证使用 App 的同一个 core 下载器，仅将当次网页媒体地址经 stdin 传入，不把签名地址写入导出证据。它不能替代 Android 的通知、MediaStore/SAF、系统播放器验收。

## 自动化验证

新增本地 MockWebServer 下载测试，覆盖真实字节传输、签名保留、无凭据、未知长度、过期/HTTP 状态、响应类型、重定向/循环/上限、超限、连接截断和主动取消。新增文件事务故障测试覆盖创建/打开/写入/关闭/长度验证/提交失败、复制中取消、提交边界取消、非法输入不创建公共文件。文件名保留 Unicode，清理非法字符并限制 UTF-8 长度。

全量 core 和 app debug/release 单元测试、debug/release 构建、lint 结果见 [v040-validation.json](v040-validation.json)。测试夹具与线上媒体分开；线上 probe 不是 JUnit 测试，不会在单测期间访问抖音。

## 实际限制与待验收

开发机 adb 没有连接手机或模拟器。未运行 Android 真机安装、后台通知、MediaStore/SAF 保存、权限拒绝/撤销、系统强停与完整音视频播放检查，不能把桌面下载成功当作这些项目通过。用户可用已提取成功的短视频优先验收：下载 → 回桌面看通知 → 播放检查画面和声音 → 再试取消。

目前一次一条，最大 2 GB；没有暂停、断点续传、批量队列或完整下载历史。重试从头开始，过期地址需重新解析。峰值存储约两份文件。后台服务仍可能被厂商省电、强停或系统限制中断。仅公开普通渐进 MP4；不支持 DRM、HLS/DASH、直播、图集、私密或需登录内容。


## 最终桌面文件结果

使用同一个 core 下载器取得完整 MP4：4,026,565 字节，SHA-256 为 c45b2dcc38909571223464e40358d981f872e42256deb811bc86c3b1c508043d。HTTPS 跳转链为 aweme.snssdk.com → v5-hl-mly-ov.zjcdn.com，签名参数未导出。

独立 Chrome 从本地文件播放到结尾，实际为 720 × 1280、25.63195 秒，音频解码字节 410,901、视频解码字节 3,586,399，未报告媒体错误。检查采用静音 4 倍速，不能当作听感或真机性能验证。

页面信息给出约 22.614 秒，而用户截图标注更高分辨率；不能把页面的标注直接当作实际文件参数。App 完成保存后现显示 Android 检查得到的实际分辨率与时长。没有修改下载参数或伪称获得更高清资源。
