# 0.3.1 真机失败样本复查

静态调查完成时间：2026-10-03 16:24，Asia/Shanghai。样本作品 ID 为 `7691911860576128310`。本文件先记录匿名公开页面及静态脚本检查，再记录独立桌面 Chrome 的正常页面加载对照；都不是该 Android 手机的网络抓包，也不证明媒体提取成功。

## 已确认的故障边界

用户的 0.3.1 诊断显示短链定位和规范链接均正常。可见 WebView 已加载移动分享页，官方网页显示“抱歉出错了”，并引导在抖音内观看；没有视频元素。诊断的“资源拦截 9 次”仅为计数，不能据此推断哪一个被拦请求导致失败。

本轮重新请求结果如下。所有页面都没有 `videoInfoRes` 或 `play_addr`。移动页的 `_ROUTER_DATA` 包含作品 ID、页面上下文和试验配置，没有媒体数据。

| 页面 | HTTP | 响应大小 | 状态 |
| --- | ---: | ---: | --- |
| [PC 作品页](https://www.douyin.com/video/7691911860576128310) | 200 | 72,914 B | 脚本壳，没有已支持媒体状态 |
| [移动分享页](https://www.iesdouyin.com/share/video/7691911860576128310/) | 200 | 32,518 B | 状态块存在，媒体数据缺失 |
| [m 分享页](https://m.douyin.com/share/video/7691911860576128310/) | 200 | 32,498 B | 状态块存在，媒体数据缺失 |

## 官方页面的客户端行为

静态检查本轮移动 HTML 实际引用的 18 个应用 JS 文件，得到以下证据：

1. 路由加载器调用页面自己的请求封装，结果写入 `videoInfoRes`。播放器转换函数在状态码非零、缺失，或者条目列表为空时进入错误分支。因此，网页加载完成也可能没有任何可播放视频。[页面入口脚本](https://lf-douyin-mobile.bytecdn.com/obj/growth-douyin-share/growth/douyin_ug/static/js/video.543ccea4.js)
2. 普通视频的客户端请求使用相对路径 `/web/api/v2/aweme/iteminfo/`，基础地址为当前页面的 HTTPS origin。对本样本就是 `www.iesdouyin.com`；0.3.1 的策略已允许该主机及路径。本次没有直接请求此接口、构造平台签名或复制平台请求实现。[页面公共模块](https://lf-douyin-mobile.bytecdn.com/obj/growth-douyin-share/growth/douyin_ug/static/js/6531.3d6a609a.js)
3. 截图中的错误文案来自通用默认分支；未登录和已登录的错误映射都有此文案。删除、私密帐号、朋友可见等情况有各自独立文案。因此不能将本截图分类为必须登录、作品已删除或特定权限限制。[页面展示模块](https://lf-douyin-mobile.bytecdn.com/obj/growth-douyin-share/growth/douyin_ug/static/js/async/video_(id)/page.5ca75285.js)

这 18 个应用资源都位于已放行的 `lf-douyin-mobile.bytecdn.com/obj/growth-douyin-share/growth/douyin_ug/static/` 前缀。本次未找到主播放器普通请求被错误拦截的直接证据。页面配置里有 11 项 `abParams`，序列化为查询串为 342 字符；这不支持“当前请求一定超过 4,096 字符”的推断，也不排除某次真实设备运行时请求更长。

## 已发现但尚未证明与本次失败有关的策略缺口

页面公共模块包含一个条件分支：响应若携带 `bdturing-verify`，会尝试展示官方验证码，并在用户完成后重试原请求。该分支使用 `verify.zijieapi.com`，并列出较旧版本验证码 SDK 的备用地址。

| 条件性资源 | 0.3.1 策略 | 证据限制 |
| --- | --- | --- |
| `verify.zijieapi.com` | 阻止 | 仅看到验证码配置，未看到本手机实际请求或响应头 |
| `lf-rc1.yhgfb-cn-static.com/obj/rc-client-security/secsdk-captcha/2.27.2/captcha.js` | 阻止 | 0.3.1 只允许另一个已观察的 4.0.10 路径 |
| `lf-rc2.yhgfb-cn-static.com/obj/rc-client-security/secsdk-captcha/2.27.2/captcha.js` | 阻止 | 同上 |
| `lf-cdn-tos.bytescm.com/obj/static/secsdk-captcha/2.27.2/captcha.js` | 阻止 | 脚本内备用配置，不等于已加载 |

这些地址来自[本轮实际引用的页面公共模块](https://lf-douyin-mobile.bytecdn.com/obj/growth-douyin-share/growth/douyin_ug/static/js/6531.3d6a609a.js)。截图没有验证码，故不能据此宣称故障已定位到验证码、自动放行整个域族，或承诺增加这几个地址即可提取成功。

HTML 和脚本还包含日志 SDK、监控、应用打开链接、推广下载链接。它们被列出或被拦截，并不构成需要放行的依据。当前计数未包含各请求的类别，因此下一版应记录有界、脱敏的类别与主机计数，以及主资源的 HTTP/网络失败；不记录查询值、凭据或任意页面文本。

## 方法与复查材料

使用 Node 的 `--use-env-proxy`、正常 TLS 校验、Android Chrome UA、25 秒超时读取三个公开页面；每页上限 2 MiB。仅额外读取移动 HTML 引用的 18 个应用静态脚本，每个上限 4 MiB。没有运行脚本、请求视频媒体、导入 Cookie、直接请求详情接口、求解验证码或使用第三方提取服务。

原始响应和工具仅位于开发目录 `work/v032-research/`，不进入 APK 或源码交付包。`summary.json` 记录 HTTP、大小、哈希和无查询参数资源地址；`inspection.json` 只保留状态结构和静态 URL 信息。关键脚本哈希：

| 文件 | SHA-256 |
| --- | --- |
| `video.543ccea4.js` | `88ec24ff4159586a82902425e394c3657760fe2dc115b154771db92a06911518` |
| `6531.3d6a609a.js` | `04b9d0c27c028c14e243c112a78f64f4bb84c9b624ee991a726bc95f281755ac` |
| `page.5ca75285.js` | `10909f383dc74c7f445ccd2d1596965a8796eca5590c275c307f977c6f7f0652` |

本次静态调查没有证明真实媒体可提取，也没有确定用户手机的九个被拦请求。后续浏览器或真机记录应单独标注测试环境及验证结果，不能把本报告作为通过依据。

## 后续正常浏览器对照

用户另行确认：同一视频在手机的普通浏览器里也无法直接播放，页面要求转到抖音。该对照说明故障并不限于拾影的 WebView；它仍不能证明拾影拦截的九次请求全部无关，也不证明每个浏览器、网络、会话均无媒体数据。

本轮另在桌面 Chrome `154.0.8037.97` 中正常访问同一移动分享页。浏览器使用独立临时 profile、移动 Chrome UA、412 × 915 视口，禁用自动播放；保留 Chromium 沙箱和证书校验。通过 CDP 启用近似 0.3.1 的请求策略和只读结果采集，观察 25 秒。没有复制登录态、手动请求详情接口、修改页面状态或签名逻辑。此环境不是 Android WebView，CDP 请求拦截的重定向覆盖范围也与 Android 回调不同。

观察到 40 次请求，其中 9 次被策略拦截。页面展示与用户截图一致的通用错误；文档状态为 complete，视频元素数量 0，状态块仍缺少 `videoInfoRes`。已允许的请求中没有记录到网络失败。以下是本桌面实验实际阻止的请求，不能直接当作用户手机九次请求的明细：

| 主机 | 脱敏路径 | 次数 | 说明 |
| --- | --- | ---: | --- |
| `lf3-cdn-tos.bytescm.com` | `/obj/static/log-sdk/collect/5.0/collect.js` | 1 | 日志 SDK |
| `lf3-short.ibytedapm.com` | `/slardar/fe/sdk-web/browser.cn.js` | 2 | 监控 SDK |
| `lf3-sec.bytetos.com` | `/obj/cookie-project-sdk/cmp.15_f46fcaab.js` | 1 | 官方隐私组件动态脚本，未在 0.3.1 静态路径清单中 |
| `lf-security.bytegoofy.com` | `/obj/security-secsdk/runtime-stable.js` | 2 | 先 XHR、后脚本；0.3.1 仅允许 `/runtime.js` |
| `mssdk.bytedance.com` | `/web/r/token` | 1 | 官方脚本自动产生的请求，本轮未放行、未检查请求内容 |
| `mssdk.bytedance.com` | `/web/common` | 1 | 同上 |
| `zlink.toutiao.com` | `/api/cb_e` | 1 | 官方页面应用跳转相关请求，本轮未放行 |

因此，动态加载中确有两个具体静态资源路径未被 0.3.1 允许，不能把所有阻止都归为分析上报。另一方面，这个浏览器没有发起当前视频的 `iteminfo` 请求；它仅自动产生一条同源作品列表请求，返回 HTTP 200，URL 长度 591。页面可能直接使用了缺少视频的服务端加载数据进行初次渲染；这是与观察相符的解释，不是已确认的服务端原因。

首轮实验没有验证放行上述静态资源能否使视频恢复；下面的第二轮专门检验这一点。不能将 `mssdk` 或全部同公司域名一起加入放行范围，并以此宣称修复。下一版诊断应把“页面明确显示错误”“尚在加载”“已找到媒体”分开，并保留有界的类别/主机统计，避免让用户对已失败页面无限等待。

桌面对照脚本为 `work/v032-live-browser.mjs`，脱敏结果为 `work/v032-live-browser/result.json`，截图为同目录 `page.png`。这些仅为开发证据，不将浏览器 profile、Cookie、原始请求头或查询参数打包交付。

## 仅放行两个已观察静态脚本的对照

2026-10-03 16:31:34，使用新的独立 profile、相同 Chrome 版本/移动 UA/视口和 25 秒观察窗口运行第二轮。仅增加两个精确地址：`lf-security.bytegoofy.com/obj/security-secsdk/runtime-stable.js` 和 `lf3-sec.bytetos.com/obj/cookie-project-sdk/cmp.15_f46fcaab.js`。其他策略未改变，未修改产品代码。

这两个脚本均返回 HTTP 200；`runtime-stable.js` 的 XHR 和脚本加载各发生一次，CMP 脚本一次。结果仍为相同官方通用错误，0 个视频元素、无 `videoInfoRes`、无当前视频 `iteminfo` 请求。自动产生的作品列表请求仍返回 200。

| 指标 | 原策略 | 增加两个静态脚本 |
| --- | ---: | ---: |
| 请求总数 | 40 | 47 |
| 策略阻止次数 | 9 | 13 |
| 视频元素 | 0 | 0 |
| `videoInfoRes` | 无 | 无 |
| 当前视频 `iteminfo` 请求 | 0 | 0 |
| 未捕获脚本异常次数 | 1 | 0 |
| 官方通用错误页 | 是 | 是 |

新增运行时随后尝试读取 `lf-security.bytegoofy.com/obj/security-secsdk/` 下 `config_207.js`、`project_207.js`、`strategy_207.js`，每个各有一次 XHR 和一次脚本请求；这些仍被原策略阻止。另新增一次被阻止的 `security.zijieapi.com/api/metrics/emit` 请求。其他原有日志、`mssdk` 和应用跳转请求继续被阻止。

因此，只增加这两个静态路径没有恢复媒体提取；修复脚本异常也不等于视频可用。这个受限对照不能证明进一步放行依赖毫无作用，更不能支持无限扩大信任范围。结合用户普通手机浏览器也无法直接播放的对照，目前仍无可稳定提取此视频的证据。

运行方式为 `node work/v032-live-browser.mjs --allow-observed-static`；脱敏结果和截图分别位于 `work/v032-live-browser-static-control/result.json`、`page.png`。浏览器已正常关闭，本轮不将新增策略用于产品。

## 不应用 App 子资源白名单的最终对照

最后使用新的无登录 profile，仍限定主文档为该作品、保留正常 HTTPS/TLS 和浏览器沙箱，但让网页按照普通浏览器方式加载其 HTTPS 子资源，不应用拾影的主机/路径白名单。未点击登录、打开外部应用或自行调用任何页面 API。

25 秒窗口内观察到 62 次请求、0 次策略拦截，页面仍显示相同官方通用错误，视频元素数量 0、没有 `videoInfoRes`、没有当前视频 `iteminfo` 请求。脚本异常和 console error 都为 0。网络记录仅有一条指标上报在收到 HTTP 204 后显示 `ERR_ABORTED`；不能将该状态推断为视频故障原因。

这次结果进一步说明，在此桌面浏览器/网络环境中，移除拾影子资源白名单也不能让该视频正常播放。它与用户普通手机浏览器的失败对照一致，仍不代表所有地区、账号或网络都会得到相同响应。

同时在真实页面内执行当时最新的固定 `BrowserCaptureScript.javascript`，只返回 `blocker`、`readyState`、`videoCount` 和捕获是否失败，不将完整 HTML 或页面状态传出浏览器。当时版本执行成功、文档 complete、视频数量 0，但新增 `webPlaybackUnavailable` 检测返回 false，发现真实 DOM 漏识别。

随后仅采集这两个已知错误标签的标签名、类名、祖先矩形和样式枚举进行定位。真实容器为 `.special-case-container`，外层为 `.special-case-wrap`；两个文字节点分别为 `.special-font-title` 和 `.special-font-subtitle`。当前错误容器选择器只覆盖 error / empty / fail 等名称，因此没有选中真实容器；所有祖先实际可见，尺寸没有问题。修订需要增加精确的官方容器类名，继续保留成对文案、可见性和作者文案排除约束，并用此结构验证。

未命中的脚本 SHA-256 为 `c154c7e30639a76aeb77a54b62a1a6868f6f92c50823e61fbff6c79d6b359c67`。该记录是发现问题的证据，不能充当修订后检测通过的证明。

普通子资源对照的结果和截图在 `work/v032-live-browser-ordinary-control/`；只含已知错误标签结构的后续定位记录在 `work/v032-live-browser-dom-check/`。两次浏览器均已关闭。除修订后对已定位错误检测的验证之外，不继续扩大平台访问调查。

## 修订后的真实 DOM 检测验证

增加精确 `.special-case-container` 选择器后，再用独立无登录 Chrome profile 加载同一实际页面，保持正常 HTTPS 子资源加载与主文档限制，观察 10 秒。页面仍显示同样的官方错误，视频数量 0；本轮 65 次请求、0 次策略拦截，没有脚本异常或 console error。

在页面内部执行修订后的固定捕获表达式，并仅返回以下诊断摘要：

```json
{
  "blocker": {
    "verification": false,
    "login": false,
    "webPlaybackUnavailable": true
  },
  "readyState": "complete",
  "videoCount": 0,
  "captureFailed": false
}
```

通过验证的脚本 SHA-256 为 `f4a150c24daa43a216060e12431286905220b7d34b2ddc7fd13804149c2a5a89`。这证明修订后的错误页检测命中了该真实网页；不代表媒体提取或 Android WebView 真机验收通过。`work/v032-live-browser-dom-check/result.json` 和同目录截图现保存这一最终验证结果，未保存完整捕获 HTML 或页面状态。浏览器已关闭，本轮调查结束。
