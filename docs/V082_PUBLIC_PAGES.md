# 拾影 0.8.2 · 快手公开页面与连接诊断

## 已知问题

用户 0.8.1 截图显示 UnsupportedPage；随后复制的一次诊断显示 PageLoadFailed、短链没有 HTTP 响应、没有作品编号。它们是不同失败步骤，不能把截图直接归因为网络。用户再次测试同一视频的完整作品地址仍失败，说明不能只归因于短链。诊断末尾 COMPLETE 对应此前另一作品 3xkyrcbm5bxbmt2，不能说明当前视频下载成功。

开发机过去成功下载过该作品，但本次重现了 HTTP 200 的公开页面只包含网站状态、不含目标作品数据。同一个作品、同一种匿名请求方式，在不同请求时间可出现成功和空目标状态。没有证据表明是分享文案、快手极速版身份或 HTTP 协议配置导致；尚无这次手机网络异常的细分类别。

## 修改

- 快手目标作品首先请求原电脑版页面。缺少目标资源或遇到可替换入口的连接错误时，尝试一次官方手机入口 https://v.m.chenzhongtech.com/fw/photo/{id}，它可能再次跳回同一电脑版作品页。全部提取请求共用 7 次预算，按当前作品编号校验。没有采用 /f/ 猜测替换：实测该短链形式跳到推荐页，未提供目标作品。
- 快手短链连接中断、连接失败或超时，可在同一入口重试一次；单次最多 10 秒，仍受原 UI 的定位 25 秒 / 全流程 60 秒上限与用户取消约束。DNS、TLS 和已有 HTTP 拒绝响应不做同主机短链重试；小红书原请求超时保持不变。
- 登录、作品不存在、访问拒绝、HTTPS 安全连接异常、站外跳转及作品编号冲突停止请求，不继续替换页面。目标绑定和 CDN 规则未放宽，原地址不改成 HTTP，不引入登录 Cookie、代理、签名生成或第三方解析。
- 诊断区分 DNS、TLS、TIMEOUT、CONNECT、CONNECTION、TOO_LARGE 和 MISSING_BODY。保留是否已收到 HTTP 响应与耗时；只记录固定类别、允许的主机和计数，不记录异常消息、页面内容、媒体 URL 或查询参数。
- 状态解析报告脚本数、状态块数、可解析状态、目标作品状态和筛选后的 MP4 候选；视频元数据和候选提取逻辑沿用。平台在短链请求失败时仍可识别为快手；短链计数明确为 HTTP 响应数。下载诊断标明与此次提取分开的任务状态。

## 实际观察

以下使用真实 ShareTextParser、PlatformVideoService、SocialExtractor 和 VideoDownloader；成功下载后独立读取 MP4 容器中的音视频轨道。失败记录保留，不将合成测试当作实测。观察时间为证据 JSON 中的 UTC 时间。

首轮：

| 样本 | 结果 | 文件 |
| --- | --- | --- |
| kuaishou-lite-user | 失败：UnsupportedPage | 未取得目标资源，未下载 |
| kuaishou-canonical-user | 失败：UnsupportedPage | 未取得目标资源，未下载 |
| kuaishou-normal-regression | 失败：UnsupportedPage | 未取得目标资源，未下载 |
| xhs-regression | 成功 | 720 × 960；2799265 字节 |

后续复测：

| 样本 | 结果 | 文件 |
| --- | --- | --- |
| kuaishou-lite-user | 成功 | 720 × 1280；3780364 字节 |
| kuaishou-canonical-user | 失败：UnsupportedPage | 未取得目标资源，未下载 |
| kuaishou-normal-regression | 失败：UnsupportedPage | 未取得目标资源，未下载 |
| xhs-regression | 成功 | 720 × 960；2799265 字节 |

默认 HTTP 协议配置与仅 HTTP/1.1 的一次匿名对比均取得目标 6 个候选，因此生产协议配置保持原样；不能从对比推断实际协商协议造成问题。证据：[v082-live-download.json](v082-live-download.json)。备用入口的顺序、拒绝访问停止、目标冲突、总请求预算、取消、超时和诊断隐私使用离线故障测试验证。当前完整检查：[v082-validation.json](v082-validation.json)。原业务范围保护：[v082-business-preservation.json](v082-business-preservation.json)。

## 真机仍需确认

开发机没有连接 Android 设备，不能确认用户手机失败已解决。0.8.2 是备用入口和诊断改进，不承诺所有快手链接立即恢复。请覆盖安装同类型包并用原完整分享文案测试；若失败，复制新版诊断确认具体主机、错误类别和目标状态计数。原 Android 文件保存、队列、通知与 UI 逻辑没有功能修改。versionCode 17，沿用开发签名，旧记录和目录选择可保留。
