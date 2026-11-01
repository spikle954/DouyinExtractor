# DouyinExtractor 项目计划

基准日期：2026-10-03（Asia/Shanghai）。本项目只处理用户有权保存的公开普通视频；所有提取逻辑在 Android 设备上运行。

## 验收原则

- Kotlin、Compose、Coroutines、OkHttp；最低 Android 8.0 / API 26。
- 不引入 yt-dlp、youtube-dl、Seal、YTDLnis、NewPipe Extractor、第三方在线解析 API 或统计 SDK。
- HTTP 200 不代表解析成功。只有实际提取到与目标视频匹配的可用媒体信息才报告成功。
- 原始网页调查与合成回归 fixture 分开存放；不将测试视频或固定媒体地址写入产品逻辑。
- 每阶段执行编译和已有测试，保存命令及结果；失败必须修复或明确报告阻塞，不能跳过检查点后宣称完成。
- release 构建与可长期分发的正式签名分开说明；不把开发密钥伪称正式发行密钥。

## 顺序

0. 调查：检查工作目录与工具链；用真实公开链接记录逐跳响应、HTTP 状态、Content-Type、HTML 结构；核查维护中项目与 issue。输出 RESEARCH.md。
1. Android 骨架：Gradle Wrapper、Compose 中文首页、ACTION_SEND/text/plain、onNewIntent、隐私入口；编译 debug/release 并运行已有测试。
2. 输入与规范化：ShareTextParser、DouyinUrlResolver、平台域名校验、短链 redirect trace、aweme_id 提取；离线测试。
3. 提取：VideoExtractor、DouyinExtractor、多个独立页面 Parser、格式排序、错误分类、脱敏诊断；fixture 测试。
4. 下载：后台前台服务/可靠任务、进度通知、MediaStore/API 29+、SAF/API 26+、文件名清理、目录持久化；编译与回归。
5. 本地 WebView：用户可选择的公开页面加载、限定导航、DOM/资源观察、明确验证提示、生命周期清理；编译与回归。
6. 收尾：全量测试、设备能力内的验证、中文说明、隐私政策、MIT、第三方清单、debug/release APK 与已知限制。

## 初始环境

工作目录只有 outputs/ 与 work/，没有已有 Android 项目。初次 PATH 检查未发现 java、javac、gradle、adb、python；已发现 Git 与系统 curl。后续调查可用工具链并记录实际结果，不预设已具备编译条件。

## 进度

- [x] 调查（匿名 HTTP 没有取得媒体字段，不能宣称解析成功）
- [x] Phase 1（编译通过；用户已提供手机首页/两条文案接收截图，未验证视频提取）
- [x] Phase 2（分享文案、URL 规范化、逐跳短链、脱敏诊断与离线测试；视频资源尚未提取）
- [x] Phase 3（提取架构、多 Parser、视频信息界面与离线回归；普通 HTTP 仍可能返回空状态，已有用户手机 WebView 提取目标媒体成功证据）
- [x] Phase 4（0.4.0 实现单任务后台下载、进度通知、MP4 检查、MediaStore/SAF、目录记忆、取消/失败清理；构建和离线测试记录另附，新版真机待验收）
- [ ] Phase 5（因真实设备 HTTP 返回空媒体，0.3.1 已提前接入可选 WebView，0.3.2 改善官方错误识别与请求诊断；0.3.3 优先视频页入口，已有用户手机成功样本；0.3.4 自动衔接普通提取和网页提取，用户已确认两个 0.3.4 随机链接自动提取成功；仍待广泛设备验收）
- [ ] Phase 6

- [x] 0.4.1：抖音公开无水印候选优先；用户确认当前版本可用。
- [x] 0.5.0：新增 B 站公开视频 MP4、BV/AV、短链、分 P、独立平台分派及下载请求；生产代码桌面实际下载验证、编译和回归完成，手机复测待用户反馈。



- [x] 0.5.1：默认 Movies/拾影 与系统视频库登记、完成位置卡片、打开保存文件夹及系统选择器回退、首页最近 50 条本机保存记录、旧版最后成功记录兼容；无新增权限或依赖，真机文件管理器/相册待复测。

- [x] 0.6.0：最多 20 个串行下载任务、逐任务资源和目录固定、取消当前/移除待办、失败或进程中断后重新解析、已保存记录搜索、同作品与分 P 的重复下载提醒及旧记录兼容；离线队列生命周期和记录回归验证，编译结果见 docs/v060-validation.json。真机队列通知与中断恢复待复测。
