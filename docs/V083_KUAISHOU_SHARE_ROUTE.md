# 拾影 0.8.3 · 快手官方作品页路由修复

## 原因与证据

用户 0.8.2 诊断显示短链已解析出 3xucdsfddb9cz4m，两次电脑版页面均 HTTP 200、状态块可解析，但目标作品状态和媒体候选均为 0。通用手机入口 302 仍回到同一电脑版页面。用户确认同一视频在手机平常浏览器可以直接播放。

本次对照发现官方手机分享页跳到电脑版作品页时会添加 location=app_share 及若干 utm_* 字段。旧 SocialUrls.normalize 只保留快手 photoId，导致清掉页面路由标记后取得网站外壳。相同匿名 HTTPS 请求中：无查询字段的作品页缺少目标状态，单独保留官方 location=app_share 即返回目标数据。逐项对照和真实下载记录见 [v083-live-download.json](v083-live-download.json)。这为此前成功/失败交替提供了具体请求差异，不再仅归为平台随机响应。

## 改动

只在受支持的 kuaishou.com/www.kuaishou.com 作品页接受单个 location=app_share 固定标记；未知值删除，重复值拒绝。它来自官方公开分享跳转，并非登录凭据、签名或用户标识。utm_*、shareToken、userId 等字段仍删除。保持原作品编号校验、HTTPS、允许的路径和 CDN 规则。

已带该路由标记的作品地址在链接识别和页面提取间保持标记；没有标记的普通作品地址仍先请求原页面，必要时经过已有官方手机备用入口取得官方路由跳转。不会在生产代码硬编码本次样本编号，也没有添加第三方解析、浏览器提取、账号登录或平台签名算法。canonical URL 和平台作品身份继续不含查询参数，队列、记录、重复提醒与文件名不变。

诊断继续隐藏全部 URL 查询字段，只补充“已保留官方分享路由标记”固定说明，不记录参数原文、页面内容、媒体地址或凭据。

## 下载与回归

以下是实际 ShareTextParser、PlatformVideoService、SocialExtractor 和 VideoDownloader 的完整文件实测；成功后独立检查 MP4 音视频轨道。极速版使用用户提供的完整原分享文案。

| 样本 | 结果 | 分辨率 | 字节 |
| --- | --- | --- | --- |
| kuaishou-lite-user | 成功 | 720 × 1280 | 3780364 |
| kuaishou-canonical-user | 成功 | 720 × 1280 | 3780364 |
| kuaishou-normal-regression | 成功 | 720 × 1280 | 1197962 |
| xhs-regression | 成功 | 720 × 960 | 2799265 |

极速版分享和纯作品地址得到同一文件 SHA-256，均为约 12 秒的 AVC 视频及 AAC 音频。普通快手、小红书文件与此前已验证文件的 SHA-256 一致。无需改动下载或文件发布逻辑。

新增固定路由值、跟踪字段删除、重复参数拒绝、解析到提取间保留、官方手机跳转、诊断查询隐藏和错误作品/不安全跳转拒绝测试。完整构建与回归：[v083-validation.json](v083-validation.json)。原代码范围检查：[v083-business-preservation.json](v083-business-preservation.json)。不将参数文本出现或合成测试当作完整下载证明。

## 安装与真机复测

versionCode 18，沿用原包名和开发签名。用户目前是 debug 包，安装 shiying-0.8.3-debug.apk 覆盖即可，无需卸载；release 用户使用同类型签名包。没有新增依赖或权限。开发机未连接 Android 目标，尚无该版手机运行验证。请复测原分享文案和纯作品链接的提取、下载及有声播放；仍失败则复制新版诊断。
