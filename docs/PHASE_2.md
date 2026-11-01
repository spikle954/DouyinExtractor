# Phase 2 验收记录

版本：0.2.0，versionCode 2。日期：2026-10-03，Asia/Shanghai。

## 完成内容

- `ShareTextParser` 从文案提取单条平台链接，处理无协议域名、中文标点、复制口令，拒绝多条不同链接和超长输入。
- `DouyinUrls` 校验精确平台主机、路径和 ID。识别普通视频、移动分享页、精选/发现 modal_id；规范输出 www.douyin.com/video/{id}。ID 保留字符串，不转 Long。
- 短链域名上的 modal_id 不作为作品身份；必须取得真实重定向目标。
- `DouyinUrlResolver` 与 `DouyinHttpClient` 分离，逐跳处理 301/302/303/307/308，支持相对、协议相对和仅查询参数的 Location。仅允许 HTTPS 平台域名，每跳请求前验证；最多 8 次跳转、循环检测、每请求 15 秒、UI 总流程 25 秒。
- 中间服务端查询参数保留，避免破坏跳转所需状态；URL 跟踪/查询参数不写入共享诊断。
- OkHttp 请求头、透明 gzip、内存 CookieJar，协程取消会调用 Call.cancel。当前不自动重试。
- 页面接入真实识别状态、取消按钮、视频编号、规范链接、中文错误和可复制诊断。编辑或收到新分享后，旧请求不能覆盖新结果。
- 版本与开发签名延续，支持覆盖升级。无新增运行时第三方依赖或敏感权限。

## 编译与离线测试

执行 `assembleDebug assembleRelease test lintDebug`，最终构建成功。第一次测试有一个边界 fixture 意外写成 27 位，而规范上限为 25 位；修正 fixture 为显式构造的 25 位后通过，没有为了测试修改有效范围。

最终执行 53 个 core 测试，以及 8 个 ViewModel 用例在 debug/release 各一次：共 **61 个独立用例、69 次执行，0 失败**。机器可读统计见 [phase2-test-summary.json](phase2-test-summary.json)，构建记录见 [phase2-build.txt](phase2-build.txt)，Lint 记录见 [phase2-lint.txt](phase2-lint.txt)。

Lint 最终为 **0 错误、6 条固定依赖版本的更新提示**。debug 与开发签名 release APK 均通过 apksigner 和 16 KiB zipalign 检查；debug 证书与 Phase 1 完全一致。详见 [phase2-apk-validation.txt](phase2-apk-validation.txt)。

测试覆盖输入规范化、aweme_id、重复/冲突 ID、恶意嵌套 URL、域名/端口/凭据边界、各种 Location、保留中间跳转查询参数、循环/上限、HTTP 状态、实际 HTTP 请求头、Cookie 域/路径/过期/替换/数量上限、实际网络调用取消、诊断脱敏、UI 请求竞态和超时。

这些测试只使用资源 fixture、注入 transport 和本机 MockWebServer，不请求线上抖音。JSON/HTML Parser、格式选择的测试属于 Phase 3，不能用本阶段的测试数代替它们。

## 真实网络核对

另行使用匿名 Node HTTP 调查（不是上述单测）：

| 用户样本 | 实际短链响应 | 视频编号 | 继续请求移动落地页 |
| --- | --- | --- | --- |
| Linda /9jWJDrnGGCI/ | 302 | 7691949525241531674 | 200，状态对象无媒体字段 |
| 第二张截图 /5AXSheWSgNw/ | 302 | 7691307792850981055 | 200，状态对象无媒体字段 |

证据及响应哈希见 [phase2-live-check.json](phase2-live-check.json)。第一张截图存在 l/I 相似字符，没有猜测后宣称线上验证。对应文本 fixture 明确注明转写的不确定性。

App 的本阶段逻辑在重定向 Location 有视频 ID 时就停止。因此其诊断通常只有一次 302 请求，**不会冒充已经访问了后面的 200 页面**。直接 /video 或 modal_id 链接可以完全在本地识别，不能证明视频可访问、存在或可下载。

## 审查修复

- 诊断补上无协议域名后直接接 ?/# 的脱敏，避免导出跟踪参数。
- 短链 modal_id 不可冒充真实编号。
- 服务端中间跳转的签名/路由参数不能被提前清理。
- Java URI.resolve 对仅查询参数 Location 会丢失末尾路径段；改用经过原始地址安全检查后的 HttpUrl.resolve，并增加回归。

## 验证边界

用户截图验证的是 Phase 1 文案接收和页面显示，没有证明旧版提取过视频。当前未连接 Android 设备；Phase 2 APK 未在本机模拟器/真机上安装运行。安装后请测试此前两条分享、再次分享不同视频、取消和诊断复制。

当前不提供封面、标题、作者、时长、MediaFormat 或下载。Phase 3 开始接入多个独立网页 Parser，仍需面对当前公开页缺少媒体字段的问题。
