# 拾影 0.8.1 · 快手极速版分享

用户提供了快手极速版“桃子邪修怎么记”的完整分享文案。0.8.0 生产流程实际返回 RedirectFailed：官方 v.kuaishou.com 短链 302 到 kph8gvfz.m.chenzhongtech.com/fw/photo/3xucdsfddb9cz4m，而旧规则仅接收固定 v.m.chenzhongtech.com。

本次只扩展快手手机分享的受控单级动态子域名和既有 /fw/photo/{id} 路由，不放开其他 chenzhongtech 域名、其他路径、用户凭据、非默认端口或站外跳转。分享参数 photoId 必须与路径一致，shareToken/userId 等跟踪参数不保留，诊断省略查询。纯链接、完整分享文案及系统 text/plain 分享使用同一入口。

确认目标作品编号后，普通快手与极速版统一使用不带参数的公开视频页提取。仍是 Kuaishou 平台身份，共用文件名、队列、搜索和重复提醒，避免同一作品在两个 App 间变成两条独立下载身份。抖音、B 站、小红书、快手页面映射、视频传输、文件保存和队列工作器沿用原实现。界面只补充“含快手极速版”的说明。

## 实测与回归

- 极速版用户样本：提取 6 个资源候选，实际下载文件 3,780,364 字节，720 × 1280，约 12.2 秒，AVC 视频 + AAC 音频轨道。
- 普通快手原样本“打了个出租车”：实际下载成功，720 × 1280，约 5.4 秒，音视频完整，文件 SHA-256 与 0.8.0 实测一致。
- 执行的是 App 的实际 ShareTextParser、PlatformVideoService 和 VideoDownloader；完整 MP4 检查后独立读取文件轨道。证据见 [v081-live-download.json](v081-live-download.json)。不将合成单测当作实测。
- 新增极速版文案、动态主机跳转、普通/极速统一身份、站外/路径/HTTP拒绝、目标冲突及诊断脱敏测试；旧测试继续通过，见 [v081-validation.json](v081-validation.json)。原代码改动范围见 [v081-business-preservation.json](v081-business-preservation.json)。

versionCode 16、沿用原包名和开发签名，可覆盖安装同类型旧包。没有新运行时依赖、权限或登录数据。原生 Android 分享面板、MediaStore/SAF 保存、播放、通知和当前布局尚无开发机设备验证，仍请手机复测。
