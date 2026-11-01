# Phase 3 验收记录（0.3.0）

本阶段完成提取模块与界面、离线测试和 APK 构建；**尚未取得真实样本的媒体地址，不能宣称抖音提取已稳定可用。**

## 实现范围

- `VideoExtractor` 与统一 `MediaInfo` / `MediaFormat`；Douyin 独立实现，便于后续增加平台。
- `DouyinPageConfig` 可维护三种公开页面策略，PC → iesdouyin → m.douyin；没有签名 API、第三方解析网站、账号模拟或登录 Cookie。
- 六个独立页面解析器；结构化 JSON 用平衡扫描和 Kotlin Serialization，HTML 用 jsoup。资源必须关联目标作品。
- 对不存在的标记、有效但无媒体的状态、结构损坏、找到资源分别诊断；多个解析器与页面可以继续回退。
- 多资源选择、封面、标题、作者、时长和已知尺寸/大小。缺失信息显示未知；HTML 播放器布局尺寸不当作文件分辨率。
- 同一资源合并非冲突属性；不同码率资源不借用主资源的属性。推荐优先 AVC 兼容性，再按已知尺寸和码率；最高质量按声明数据排序。
- 网络正文最多 2 MiB（包括 gzip 解压后），图片最多 4 MiB；JSON 深度、遍历节点和资源数量有上限。超过上限不会使用可能缺少竞争视频的部分扫描结果。
- 页面重定向和解析诊断脱敏，不导出播放 URL、Cookie 或原始 HTML；取消、超时和新分享使旧任务失效。
- 下载、存储目录、通知栏进度、MediaStore/SAF 和本地 WebView 尚未实现，下载按钮不可用。

## 真实证据与边界

用户截图确认 Phase 2 在手机上分别识别到 `7692025418936749322` 和 `7687957792199747682`。开发机复查的两个短链均 302 → 200；6 个直接页面路径及 2 条短链路径均无媒体信息。PC 为 72,914 字节脚本壳；移动页面只有 `_ROUTER_DATA` 的上下文、作品 ID 和实验设置，没有视频对象或播放地址。

详见 [PHASE_3_RESEARCH.md](PHASE_3_RESEARCH.md) 与 [phase3-live-check.json](phase3-live-check.json)。因此当前真实样本只能验证「链接识别」及「真实空页面正确失败」。合成成功 fixture 只验证结构映射，不证明当前平台会返回同样的数据。

下一项需要独立验证的能力是手机上正常加载动态公开页面后的媒体状态；按原定阶段属于 Phase 5 本地 WebView。Phase 4 下载模块仍须对真正取得的资源验证有效性，不能将测试文件作为用户视频交付。

## 构建与回归

执行 `assembleDebug assembleRelease test lintDebug`。使用固定 JDK 17、Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20、SDK 35。

最终机器可读结果见 [phase3-validation.json](phase3-validation.json)。测试包含 Phase 2 的全部回归，以及：

- 六种 Parser 的合成 fixture、真实空 Router fixture、损坏状态后回退、不同视频混杂、数据限额。
- 资源 URL 边界、MP4 格式策略、DRM/音频/图集排除、不同清晰度属性与格式排序。
- MockWebServer 的短链、页面跳转、会话 Cookie、gzip/分块响应上限、读正文期间取消。
- ViewModel 的自动提取、空结果/错误 ID 拒绝、过期任务竞争、取消、超时、格式选择。
- 诊断查询参数和凭据脱敏、图片类型和大小限制。

`adb devices -l` 无设备，未进行 Phase 3 真机或模拟器 UI 实测。编译/单元测试结果不能替代设备运行证据。

## 交付与签名

- 推荐 `shiying-phase3-debug.apk`，包名 `io.github.douyinextractor.debug`，versionCode 3，可覆盖此前同类型 APK。
- R8 release 的开发签名测试包与 unsigned 包另行提供；unsigned 不能直接安装。
- debug 及开发签名 release 沿用相同证书 SHA-256：`0f4e4a0d56d883b69c27c4d1a257678055533aa943a7ee46987da82f616b3e18`。不是正式发行证书。
- 源码归档不含 build/cache/local.properties/密钥、原始页面或工具链。
- 无新增依赖和 Android 权限。依赖更新建议属于后续独立维护，未在本阶段盲目升级。

封面采用尺寸预读与采样解码，依据 [Android 官方图片内存说明](https://developer.android.com/topic/performance/graphics/load-bitmap)。
