# 拾影 0.7.1 · A 版真机截图修正

用户提供了 0.7.0 首页、下载记录、输入法和解析结果四张截图，并确认记录中的视频正常播放。已将 A 参考单屏与实际界面按等宽、保持纵横比并排检查；手机 dp 密度、字体缩放和系统栏裁切信息未知，因此没有把参考图与手机的绝对像素大小差异当作问题。

本次三个修正：

1. 系统缩略图提供程序失败时，Android 8.1+ 尝试从记录对应的本地 content URI 解码缩小的一帧。只处理已有授权文件，IO 线程执行、最多 2 个并发、96 × 128 px 目标、4 MB 内存缓存，原生资源总会尝试释放。Android 8.0 或失败时保留视频图标。不保存远程封面、不加权限或依赖、不改变历史格式。
2. 记录的资源和时间都为空时不生成空白文字行；已有“资源 1”直接显示，避免“资源：资源 1”。完整文件信息仍能从更多菜单查看。
3. 解析结果的下载按钮固定于可视内容底部，上方摘要、资源、保存目录与队列信息独立滚动。仍只保留一个 DownloadPanel 的状态、权限和目录选择器；按钮调用原重复检查和提交动作，不复制下载逻辑。大字体下按钮允许增长，底部位置仍使用系统安全区域。

Android API 依据：[ContentResolver.loadThumbnail](https://developer.android.com/reference/android/content/ContentResolver#loadThumbnail(android.net.Uri,%20android.util.Size,%20android.os.CancellationSignal))、[MediaMetadataRetriever.getScaledFrameAtTime](https://developer.android.com/reference/android/media/MediaMetadataRetriever#getScaledFrameAtTime(long,%20int,%20int,%20int))。后者 API 27 起支持指定缩小尺寸及 content URI 数据源；实际能否取帧取决于文件提供程序与系统解码器。

所有原 core、网页提取、MainViewModel、DownloadRuntime、下载工作器、前台服务、文件保存、历史编码/搜索、重复检测与文件操作保持原实现。核对见 [v071-business-preservation.json](v071-business-preservation.json)，编译与回归见 [v071-validation.json](v071-validation.json)。versionCode 14，沿用原签名和 debug/release 包名。

## 手机复测

覆盖安装同类型 0.7.1 APK，无需卸载：

1. 打开首页与下载记录，等待片刻，检查原来能播放的视频是否出现本地封面；再确认播放、搜索、打开文件夹和分享正常。
2. 使用上次的长标题链接解析，确认下载按钮完整可见；滚动资源列表、切换资源与保存目录，再点击下载，确认原重复提醒正常。
3. 下载一个新视频，截取保存成功底部弹层；尝试文件夹、播放、分享和关闭。
4. 在最大字体及横屏下检查按钮是否完整可读，上方内容是否可滚动。首页弹出输入法后确认粘贴、清空、解析与返回正常。

当前没有连接可操作 Android 设备或模拟器。上述 0.7.1 行为尚未在真实解码器和窗口上复核；不得将 0.7.0 截图当作 0.7.1 修正后的截图。视觉验收状态仍为 blocked，见 [design-qa.md](../design-qa.md)。
