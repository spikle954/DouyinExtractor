# 抖音公开分享页面调查

调查日期：2026-10-03 14:29–14:35，Asia/Shanghai。下面的 HTTP 观察来自本机网络出口、匿名 Android Chrome UA；不等同于所有手机、地区、网络的结果。

## 1. 链接类型

实际请求了：

- 用户提供的短链 `https://v.douyin.com/9jWJDrnGGCI/`。
- 从该短链得出的移动分享页 `/share/video/7691949525241531674/`。
- PC 规范地址 `https://www.douyin.com/video/7691949525241531674`。
- 精选页 `https://www.douyin.com/jingxuan?modal_id=7691949525241531674`（使用同一 ID 构造来验证路由，非另一条用户分享）。
- `https://m.douyin.com/share/video/7691949525241531674`。
- 搜索公开可见的中国新闻社视频 `7648452057669717267` 的 PC 和移动分享页作为第二样本。

识别范围会包含无 www 的 douyin.com、带/不带协议的分享文本及 v.iesdouyin.com 短链兼容路径；后者尚未拿到本次真实短链样本。图文、直播、合集、长剧不属于本期支持范围，不能误当普通视频。

## 2. 实测重定向

```text
GET https://v.douyin.com/9jWJDrnGGCI/
  302 text/html; charset=utf-8
  Location: https://www.iesdouyin.com/share/video/7691949525241531674/?…
GET 上述 Location
  200 text/html; charset=UTF-8
  35,851 bytes
```

Location 带 region、mid、did、iid、share_sign、utm_* 等参数。证据摘要移除这些值；产品内部保留跳转所需的临时请求地址，提取 ID 后用干净规范 URL。绝不把完整跟踪参数或 Cookie 复制到共享诊断中。不能提前删除一个未知短链的全部参数后才跟随，否则可能改变跳转语义。

独立请求无跟踪参数的 iesdouyin 分享页：200、32,518 bytes。m.douyin 分享页：200、32,486 bytes。上述大小是本次响应值，不作为代码判断条件。

## 3. 当前实际数据结构

移动页包含 `window._ROUTER_DATA`，可解析为 JSON，结构为：

```text
loaderData
  video_layout: null
  video_(id)/page
    itemId: 7691949525241531674
    lastPath
    commonContext
    renderInSSR: 1
    abParams
    isVideoOptimize
errors: null
```

**该对象没有 videoInfoRes、aweme_detail、play_addr 或 bit_rate。** 用户短链保留参数、移除参数和第二公开视频样本都未取得媒体字段。两个移动样本均有 36 个 script；包含 captcha 相关脚本引用，但仅此不足以证明当前用户必须过验证码。

PC 视频页、精选页和第二视频 PC 页均返回 HTTP 200、72,914 bytes、2 个 script；没有 _ROUTER_DATA、RENDER_DATA、SIGI_STATE、JSON-LD 等标记。主体为空并加载混淆 JavaScript（出现 `_$jsvmprt`）。可确认匿名 HTTP 未得到视频数据；是否为浏览器挑战需在真实浏览器加载后确认，不能单凭 200 或脚本名称判定视频删除。

本次没有在这些响应中实测到 RENDER_DATA、SIGI_STATE、JSON-LD 或包含媒体信息的 hydration 数据。它们是兼容性 Parser 的候选输入，不是本次已验证的成功路径。

## 4. 旧方案的状态

- “找到 _ROUTER_DATA 就能从固定路径取视频”：**在本次样本失败**，对象存在但没有媒体字段。
- “移动端 UA + iesdouyin SSR 一定包含 videoInfoRes.item_list”：**在本次匿名请求不成立**。
- “PC HTTP 200 就代表获取到了正常作品页”：**不成立**，200 可能只有脚本壳。
- “RENDER_DATA 永久存在”以及“取到第一个 mp4 就是目标视频”：不能作为设计前提。
- 没有证据证明所有旧接口在所有网络环境中都已失效，不能作这种泛化结论。

研究参考（只借鉴问题与公开格式，不引入/复制项目代码）：

- [DouyinDL 1.2.0，2026-05-13](https://github.com/noctiro/DouyinDL/releases)。仓库许可证 AGPL-3.0，不将其代码复制进本 MIT 项目。
- [DouyinDL issue #2，2026-08-16](https://github.com/noctiro/DouyinDL/issues/2) 报告失效但未提供描述；[issue 列表](https://github.com/noctiro/DouyinDL/issues) 的 #3 于 2026-09-09 报告视频信息为空。这是用户报告，不是完整复现实验；#3 正文未成功取得。
- [douyin-local-downloader](https://github.com/enjoy-loneliness/douyin-local-downloader) 描述页面状态、内联 JSON 和浏览器观察的多级路径，说明不能只依赖单一标记。本项目不用其同源详情接口请求方案。
- [media-parser 的 Douyin 研究文档](https://github.com/ucmao/media-parser/blob/main/docs/parsers/douyin.md) 提到 SSR 空壳、嵌套 JSON 提取、空业务响应等问题。其“SSR 总能返回媒体”等成功率说法未在本次样本得到验证。本项目不引入其签名、Cookie 导入、私有 API 或播放 URL 改写方式。

## 5. 解析架构

```text
Compose / 系统 ACTION_SEND
  → ShareTextParser（从文案提取受支持平台链接）
  → DouyinUrlResolver（限定平台域名、逐跳追踪、统一 aweme_id）
  → VideoExtractor / DouyinExtractor
      A. 公共 PC 页面
      B. 可配置的移动分享页
      C. 用户选择的本地 WebView 页面加载与只读观察
  → DouyinPageParser
      RouterDataParser / RenderDataParser / SigiStateParser
      ScriptJsonParser / JsonLdParser / HtmlMediaParser
  → 目标 ID 匹配 + MediaInfo / MediaFormat + 推荐排序
  → 下载服务 → MediaStore 或 SAF
```

每个 Parser 返回独立结果与失败原因。脚本赋值中的 JSON 用边界扫描与字符串转义处理，随后交给 JSON 库；不执行不可信脚本来提取 JSON。DOM 用 HTML Parser。限制网页大小、递归深度、跳转次数、超时和重试次数。返回无格式的状态不能作为成功。关联不明的推荐视频资源必须排除。

只下载网页实际公开给出的资源；不将 playwm 改成 play、不伪造画质参数、不获取 DRM 密钥。HLS/DASH、分离音视频、blob URL 明确标记暂不支持，不能把播放清单存成 mp4。

WebView 不默默登录、不自动解验证码、不使用全域 JavaScript bridge。优先 evaluateJavascript 获取公开 DOM/state，限定平台导航与媒体域，关闭文件/内容访问，处理生命周期和超时。无法取得资源就显示真实失败与脱敏诊断。

## 6. 目录规划

```text
DouyinExtractor/
├── app/                         # Android、Compose、分享入口、下载与 WebView
│   └── src/main/java/io/github/douyinextractor/
│       ├── ui/
│       ├── share/
│       ├── download/
│       └── webview/
├── core/                        # 纯 Kotlin/JVM，可离线单测
│   ├── src/main/kotlin/io/github/douyinextractor/core/
│   │   ├── model/
│   │   ├── input/
│   │   ├── network/
│   │   ├── extractor/douyin/parser/
│   │   └── diagnostics/
│   └── src/test/{kotlin,resources}/
├── gradle/wrapper/
├── docs/                        # 中文说明、阶段验收、研究摘要
├── README.md
├── PROJECT_PLAN.md
├── RESEARCH.md
├── PRIVACY.md
├── THIRD_PARTY_NOTICES.md
└── LICENSE
```

## 当前结论与下一步

已验证输入链接与 aweme_id；**尚未从真实样本取得可下载视频地址，不能声称稳定支持抖音**。进入 Phase 1 建立可编译的原生项目；后续用本次空状态响应建立失败回归，再验证本地 WebView 正常渲染是否公开加载媒体数据。

网络工具最初遇到 Windows Schannel 凭据错误，使用 Node 内置 TLS 和环境代理后成功。没有关闭 TLS 校验，也没有将用户链接发送给第三方解析服务。

逐次 HTTP 响应的脱敏元数据、结构标记和原始响应 SHA-256 保存在 [docs/research](docs/research)。原始 HTML 仅保留在本机 work/research，不放进开源源码包；其中包含平台生成的标识和脚本内容。摘要中隐藏了重定向查询参数，因此不同原始响应的无参数 URL 可能相同，bytes 与 hash 仍对应各自原始响应。
