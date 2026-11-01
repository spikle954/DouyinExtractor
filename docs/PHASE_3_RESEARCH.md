# Phase 3：真实页面复查

调查时间：2026-10-03 15:29:48，Asia/Shanghai。结论仅适用于本次开发机匿名请求，不能外推到所有手机、账号或网络。

## 本次结论

用户截图中的两个链接均可完成短链跳转，视频 ID 与截图一致。**8 条请求路径没有一条取得标题、作者、封面或可播放视频资源。** 移动页虽然有可解析的 `_ROUTER_DATA`，但只有页面上下文和实验开关；PC 页只有脚本壳。这是页面状态为空的真实失败证据，不是视频提取成功，也不能据此断定视频被删除、需要登录或触发验证码。

截图上的“链接已识别”只说明 Phase 2 正确取得作品 ID。Phase 3 必须取得与该 ID 对应的媒体信息后才能显示“视频信息已提取”。

## 样本和响应

| 样本 | 请求 | HTTP / Content-Type | 最终页面大小 | 状态 |
| --- | --- | --- | ---: | --- |
| Raven | `https://www.douyin.com/video/7692025418936749322` | 200 / `text/html` | 72,914 B | 2 个内联脚本；无已知状态标记 |
| Raven | `https://www.iesdouyin.com/share/video/7692025418936749322/` | 200 / `text/html; charset=UTF-8` | 32,518 B | 36 个 script；有效 JSON 无媒体字段 |
| Raven | `https://m.douyin.com/share/video/7692025418936749322` | 200 / `text/html; charset=UTF-8` | 32,486 B | 36 个 script；有效 JSON 无媒体字段 |
| 动漫 | `https://www.douyin.com/video/7687957792199747682` | 200 / `text/html` | 72,914 B | 2 个内联脚本；无已知状态标记 |
| 动漫 | `https://www.iesdouyin.com/share/video/7687957792199747682/` | 200 / `text/html; charset=UTF-8` | 32,518 B | 36 个 script；有效 JSON 无媒体字段 |
| 动漫 | `https://m.douyin.com/share/video/7687957792199747682` | 200 / `text/html; charset=UTF-8` | 32,486 B | 36 个 script；有效 JSON 无媒体字段 |
| Raven 短链 | `https://v.douyin.com/wSBRL63s87s/` | 302 → 200 | 35,941 B | 跳转上述 iesdouyin Raven 地址，携带分享参数；仍无媒体字段 |
| 动漫短链 | `https://v.douyin.com/zwSH5EHjgVs/` | 302 → 200 | 35,921 B | 跳转上述 iesdouyin 动漫地址，携带分享参数；仍无媒体字段 |

短链 302 响应类型均为 `text/html; charset=utf-8`。最终页面类型均为 `text/html; charset=UTF-8`。报告省略 Location 中的分享跟踪参数；实际网络跳转保留允许域名上的原始 Location，以免改变语义。页面大小只作为本次证据，不作为产品判断条件。

机器可读响应、脚本数量、哈希和无值结构摘要见 [phase3-live-check.json](phase3-live-check.json)。

## 实际 `_ROUTER_DATA` 结构

使用识别字符串、转义符和配对括号的扫描器取得完整对象，再用 `JSON.parse` 解析；不执行页面 JavaScript，不用正则截取嵌套 JSON。

```text
loaderData
  video_layout: null
  video_(id)/page
    ua, isSpider, webId, query
    renderInSSR, lastPath, appName, host, isNotSupportWebp
    commonContext: 与页面上下文相同的键
    isAiDouyinOpt, itemId
    isVideoOptimize, openDirectGroup, isVideoStyleOptimize
    isButtonOptimize, isAutoOpenApp, darkModeAdaptation
    serverToken
    abParams: 页面布局、打开 App 等实验参数
errors: null
```

所有移动响应的完整状态树均不存在 `videoInfoRes`、`aweme_detail`、`awemeDetail`、`video`、`play_addr`、`playAddr`、`bit_rate`、`bitRate`、`item_list`、`itemList` 或 `aweme_list`。PC HTML 不含 `_ROUTER_DATA`、`RENDER_DATA`、`SIGI_STATE` 或 `application/ld+json`。因此本轮没有成功媒体样本可用于宣称解析成功率。

## 对实现和测试的约束

- 状态标记存在、HTTP 200、URL 中有作品 ID，都不能单独判为成功。成功候选必须绑定目标作品 ID，包含直接媒体地址，并能说明资源来源和已知属性。
- PC 脚本壳和移动页空状态应产生明确诊断，继续尝试其他公开页面；所有来源均为空时提示用户页面未返回视频信息，保留“链接已识别”的结果。
- `_ROUTER_DATA`、`RENDER_DATA`、`SIGI_STATE`、JSON-LD 等格式的正向测试如果使用手写数据，必须标记为合成契约测试；它们不证明本次真实视频已可提取。
- 真实失败脱敏夹具已生成于开发工作目录 `work/phase3-research/empty-router-sanitized.html`：仅保留原始状态结构；删除外围脚本；清空 UA、webId、serverToken、query；替换作品 ID 为虚构测试 ID。该夹具明确标注 **SANITIZED REAL FAILURE**，可复制进测试资源。
- 页面返回空数据时，不构造媒体 URL，不调用签名、登录态或私有详情 API，不假定改写播放地址即可得到无水印或更高清资源。

## 方法和隐私

Node `fetch --use-env-proxy`；PC 页使用桌面 Chrome UA，其余使用 Android Chrome UA；仅匿名 HTTPS；精确平台主机白名单；最多 8 次重定向；每次请求超时 25 秒；响应上限 2 MiB。未登录、未导入 Cookie、未执行 JavaScript、未访问私有接口或第三方解析服务、未下载媒体。

原始 HTML 和原始状态仅存于开发用 `work/phase3-research/`，不放入 APK 或源码交付包。共享报告仅包含去除查询参数的页面地址、状态结构类型、计数与响应哈希；不包含 webId、分享参数值、Cookie 或用户凭据。
