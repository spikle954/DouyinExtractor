# 拾影 0.8.0 · 快手与小红书

在 0.7.1 基础上新增两个平台的原生 Kotlin 提取器及匿名页面客户端，接入原平台路由、下载和封面地址边界、文件名、队列恢复、记录搜索、重复提醒和重试入口。A 版界面、现有保存与前台服务流程保留；没有新运行时依赖或权限。versionCode 15，包名和开发签名沿用。

## 实际样本

| 平台 | 用户分享样本 | 实际文件 | 轨道 | 结果 |
| --- | --- | --- | --- | --- |
| 快手 | 打了个出租车 | 720 × 1280，约 5.4 秒，1.20 MB | AVC 视频 + AAC 音频 | 实际生产下载成功 |
| 小红书 | 在网上买了一个耿鬼开关 | 720 × 960，约 12.6 秒，2.80 MB | AVC 视频 + AAC 音频 | 实际生产下载成功 |

执行的是 App 相同的 ShareTextParser → PlatformVideoService → VideoDownloader，检查完整 MP4 后独立读取文件的轨道/尺寸/时长。没有向第三方解析服务提交链接；没有用合成 fixture 冒充实测。证据见 [v080-live-download.json](v080-live-download.json)。这是开发机生产核心流程验证，不包含 Android MediaStore/SAF 保存、播放器、系统分享或后台通知的真机结果。

## 页面与访问

快手支持 v.kuaishou.com、kuaishou.com/f/、short-video 及 v.m.chenzhongtech.com/fw/photo 路由。直接定位 ID 后读取官方页面，解析匹配的 VisionVideoDetailPhoto 和有限 Apollo 引用，包括公开 MP4 主地址与备用线路。作者来自引用同一目标 photo 的 detail，不能取推荐视频。

小红书支持 xhslink.com/xhslink.cn、discovery/item、explore。用户样本的电脑版匿名入口要求登录，普通手机分享页提供公开状态，因此选用手机网页请求；同时支持桌面 noteDetailMap 与手机 noteData.data.noteData 结构。只接受与目标 noteId 匹配的视频，不遍历评论、推荐或任意 video 标签替代目标。

访问参数 xsec_token/xsec_source 只保留在本次内存提取引用中，canonicalUrl 是不含参数的规范链接。查询签名不重编码；页面明文媒体地址只在允许 CDN 上升级成同资源 HTTPS，生产下载仍逐跳拒绝 HTTP。小红书重启后的重试会提示重新复制分享链接，避免尝试恢复访问参数。

SSR 数据只做有界 JSON 解析：2 MiB 页面、64 层嵌套、96 个脚本、有限资源/引用数；只将字符串外的 bare undefined 转为空值，不执行页面 JavaScript。公开页面拒绝、登录要求、分享不可用、作品不存在和图文笔记分别有明确失败提示。

## 支持范围

首版支持无需登录的普通公开视频及完整渐进式 MP4。暂不支持图集/图文、直播、付费/私密/登录限制、加密、HLS/DASH、分段或独立音频。不是所有公开作品都保证提供匿名网页资源，平台和地区访问限制仍会影响结果。资源水印未知时如实显示，画质和水印以实际文件为准，不宣称全部无水印。

## 回归与保留

新测试覆盖分享识别、URL/域名/跳转边界、目标绑定、两种小红书状态、访问参数脱敏、Apollo 引用与环、HTTP→HTTPS、非视频/DRM/分段拒绝、响应大小、真实网络取消、队列恢复、平台搜索/去重和 ViewModel 传递临时访问引用。完整构建、旧测试回归和原模块指纹见 [v080-validation.json](v080-validation.json) 与 [v080-business-preservation.json](v080-business-preservation.json)。

调研的数据字段背景参考：[yt-dlp 小红书提取器](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/xiaohongshu.py)、[KS-Downloader 页面字段](https://github.com/JoeanAmier/KS-Downloader/blob/master/source/extract/extractor.py)。生产实现为本项目独立编写的 Kotlin，不集成这些工具或运行时，实际适配以本次官方页面观测为准。

手机复测步骤见 [使用说明](使用说明.md)。两平台的完整分享接收、保存、播放与队列交接仍待用户手机验证；原 A 版视觉验收记录继续保留为未通过，不把本次核心下载结果当作界面截图证据。
