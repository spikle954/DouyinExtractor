# 可见网页辅助提取：样本复查与实现依据

调查时间：2026-10-03 16:00，Asia/Shanghai。样本为用户提供的作品 `7683370277783000950`。本报告是开发机匿名公开页面调查，不是 Android WebView 真机验收记录，也不证明已经取得可播放视频。

## 故障证据

| 页面 | HTTP / 类型 | 响应大小 | 结果 |
| --- | --- | ---: | --- |
| [PC 作品页](https://www.douyin.com/video/7683370277783000950) | 200 / text/html | 72,914 B | 脚本壳，无已支持状态标记 |
| [移动分享页](https://www.iesdouyin.com/share/video/7683370277783000950/) | 200 / text/html; charset=UTF-8 | 32,518 B | `_ROUTER_DATA` 存在，无 `videoInfoRes` / `play_addr` |
| [m 分享页](https://m.douyin.com/share/video/7683370277783000950/) | 200 / text/html; charset=UTF-8 | 32,498 B | 与移动分享页相同的空媒体状态 |

移动 HTML 没有静态 iframe。它包含验证码 SDK 配置，但这不等于页面已经要求用户完成验证码。HTTP 200、数据标记存在、作品 ID 正确均不能代替媒体提取成功。

匿名响应设置了浏览器 Cookie：PC 页出现 `__ac_nonce`，移动页出现 `ttwid`。调查没有导入、保存到交付物或打印 Cookie 值；仅记录名称。此事实说明网页可能维护浏览器会话，不能推断登录是必要条件。

公开移动页实际引用的入口 `video.543ccea4.js` 包含客户端路由加载器；静态阅读可见其取得 `videoInfoRes`，之后页面组件从作品条目的 `video.play_addr`、封面、作者等字段渲染播放器。因此，允许官方网页在可见的本机 WebView 中完成正常加载，再读取已渲染 DOM 和已公开的页面状态，有依据。没有执行或复制平台签名逻辑，也没有直接请求私人详情接口。入口包含这条代码路径，不代表该路径在此网络、此账号或 WebView 上一定返回视频。

## 本轮实际出现的资源域和路径

这些是一次页面快照中的证据，可用于收窄资源策略；不是所有同公司域名都安全、必要或已被访问。`src`、`link`、配置字符串和 DNS 预解析必须区分。除以下明确角色外，不应因为某主机包含 `byte`、`douyin`、`zijie` 就放行。

| 精确主机 | HTML 中的路径 / 角色 | 实现建议 |
| --- | --- | --- |
| `lf-douyin-mobile.bytecdn.com` | `/obj/growth-douyin-share/growth/douyin_ug/static/` 下 JS、CSS、动态 chunk | 主页面静态资源；限制路径前缀 |
| `lf-c-flwb.bytetos.com` | `/obj/rc-client-security/web/glue/`、`/obj/rc-client-security/web/dn/` | 官方页面安全运行时配置；仅按观察到的前缀评估，不对整个域族放行 |
| `lf-security.bytegoofy.com` | `/obj/security-secsdk/runtime.js` | 官方页面安全运行时 |
| `lf1-cdn-tos.bytegoofy.com`、`lf3-cdn-tos.bytegoofy.com`、`lf6-cdn-tos.bytegoofy.com` | `/obj/goofy/secsdk/secsdk-lastest.umd.js` | HTML 内的备用 SDK 地址，未证明实际发起请求 |
| `lf-rc1.yhgfb-cn-static.com`、`lf-rc2.yhgfb-cn-static.com` | `/obj/rc-verifycenter/sec_sdk_build/4.0.10/captcha/index.js` | 验证码 SDK 配置；不自动完成或绕过验证 |
| `sf1-cdn-tos.douyinstatic.com` | `/obj/eden-cn/kpchkeh7upepld/fe_app_new/` | favicon、启动图资源 |
| `privacy.zijieapi.com` | `/api/web-cmp/sdk/` | 网页隐私/同意组件；不能仅凭域名判断为视频资源或分析收集接口 |
| `lf3-cdn-tos.bytescm.com` | `/obj/static/log-sdk/collect/5.0/collect.js` | 明确的日志收集 SDK，可阻止加载 |
| `lf3-short.ibytedapm.com` | `/slardar/fe/sdk-web/browser.cn.js` | 监控 SDK 预加载地址，可阻止加载 |
| `mon.zijieapi.com` | `/monitor_browser/collect/batch/security/` | 页面脚本中的监控上报地址，可阻止请求 |
| `mon.snssdk.com`、`mcs.snssdk.com` | DNS 预解析地址 | 监控/日志候选；无媒体用途证据，不进入媒体白名单 |
| `lf-cdn-tos.bytescm.com`、`lf3-short.bytegoofy.com` | DNS 预解析地址 | 本次没有具体必要资源证据，不能因此扩大白名单 |

动态执行可能产生更多域名；应只记录脱敏后的主机与计数供排查，不能自动把被阻止主机加入白名单。阻止日志 SDK 可能影响站点行为，仍需真实设备测试。

## Android 官方 API 核实

- JavaScript 默认关闭；动态网页需要显式启用。官方特别警告，对不受应用控制的网页暴露 `addJavascriptInterface` 会形成原生调用攻击面。本功能不需要 JS 到 Android 的桥接。[WebView 指南](https://developer.android.com/develop/ui/views/layout/webapps/webview)
- `evaluateJavascript` 在当前页面上下文异步执行，调用和回调在 UI 线程。只执行应用自有、固定、只读、有限大小的快照脚本；读取结果后用既有解析器校验目标作品 ID；不得把页面文本作为可执行代码拼接进去。[WebView API](https://developer.android.com/reference/android/webkit/WebView#evaluateJavascript(java.lang.String,%20android.webkit.ValueCallback%3Cjava.lang.String%3E))
- 应关闭文件和 ContentProvider 访问，禁止混合明文内容，保留 Safe Browsing；不打开来自网页的任意外部 scheme、下载、文件选择或新窗口。安全设置只是基础，还需固定导航目标。[WebSettings API](https://developer.android.com/reference/android/webkit/WebSettings)
- `shouldInterceptRequest` 不覆盖 `javascript:`、`blob:`，并且只收到重定向链的初始资源 URL。`shouldOverrideUrlLoading` 不覆盖应用主动 `loadUrl`，也不覆盖 POST 导航。因此，单独两个回调不能被描述为完整网络防火墙或绝对禁止一切外发。所有应用入口应独立校验；主文档变化后应复核来源和目标作品；需要对所有资源重定向严格控制时，应使用受控网络实现。[WebViewClient API](https://developer.android.com/reference/android/webkit/WebViewClient)
- Service Worker 有单独的全局控制器；其客户端应在开始加载页面前配置。不能假设普通 WebViewClient 自动覆盖 Service Worker 请求。[ServiceWorkerController API](https://developer.android.com/reference/android/webkit/ServiceWorkerController)
- 第三方 Cookie 可以按 WebView 禁用。Cookie 删除是异步操作，应在清理完成后再启动新的隔离会话。不复制系统浏览器/抖音 App 的登录态，不调用 Cookie 读取接口生成诊断，不声称 WebView 存储天然只在内存。[CookieManager API](https://developer.android.com/reference/android/webkit/CookieManager)

可见网页会直接与抖音及其页面资源服务器通信。准确的产品说明是“拾影没有自建上传服务、分析 SDK 或广告 SDK；官方网页仍按其自身规则通信”，不能把网页辅助加载描述为完全离线或平台完全不接收数据。用户完成正常网页验证可以重读页面；不得自动解题、代登录或隐藏操作。

## 隐私与可复查性

调查使用 Node 的环境代理支持、正常 HTTPS 校验、Android Chrome UA、25 秒超时和 2 MiB HTML 上限。只读取三个公开页面和它们实际引用的五个静态 JS；静态 JS 每个上限 4 MiB。没有执行平台 JS、调用详情 API、请求视频媒体、修改账号状态或上传用户内容。

原始 HTML 和静态资产仅位于开发工作目录 `work/fallback-research/`，不会进入 APK 或源码包。交付报告不含页面状态中的浏览器 ID、会话 token、Cookie 值、分享查询参数或原始脚本代码。

## 本地脚本验证与设备限制

2026-10-03 16:07，直接从 `BrowserCaptureScript.kt` 的固定字符串提取脚本，在 Node V8 `vm` 中执行，配以明确标注的合成 DOM 桩。21 项检查全部通过，覆盖返回 JSON、文档状态、实时视频属性、可见性、身份冲突、canonical 冲突、验证和登录弹层、访问器/`toJSON` 不执行、循环引用、深度及大小预算、超大 HTML 舍弃、捕获失败降级；每次执行同时断言未读 Cookie、未调用 fetch。此项验证只证明这些脚本逻辑分支，不能证明浏览器加载、平台响应或 Android UI 行为。

脚本 SHA-256：`91d6107970e249f12c7a40857a81f98eaf99eae2e951c544aa184da63dd6d724`。若脚本修改，应重新运行开发工作目录中的 `work/fallback-capture-vm-check.mjs`，再更新本记录。

现有 Chrome 和 Edge 均尝试以 `--headless=new --disable-gpu`、各自新的工作目录 profile 加载没有外部请求的合成页面。两者均因 Windows 环境内的 GPU 进程访问错误退出，没有输出 DOM，因此没有浏览器运行通过记录。未关闭浏览器沙箱、未关闭 HTTPS 验证、未安装新浏览器。当前也没有 Android 设备或模拟器连接；真机 WebView 验收仍未完成。
