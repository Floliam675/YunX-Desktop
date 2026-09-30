# 移植说明（Android → 桌面）

## 来源
- 原项目：https://github.com/CYQawa/YunX（YunX 云析，AGPL-3.0，Kotlin + Jetpack Compose + Room + OkHttp）
- 移植策略：Compose Multiplatform Desktop(JVM)，最大化复用纯 Kotlin 业务逻辑

## 各层移植对照
| 原 Android 层 | 桌面替换 | 说明 |
| --- | --- | --- |
| data/network/*（API/常量/解析） | core 直接复用 | 纯 Kotlin + OkHttp + org.json |
| data/repository/*Resolve | core 直接复用 | 分享解析仓库 |
| Room（account/task/bookmark DAO） | JSON 文件 + StateFlow store | DriveAccountStore / JsonDownloadTaskStore |
| SharedPreferences | JSON 设置文件 | SettingsStore |
| AndroidKeyStore AES-GCM | 密钥文件 AES-GCM | FileCredentialCipher |
| WebView 登录抓 Cookie | 内嵌 JavaFX WebView 网页登录 | 登录后自动读 document.cookie / localStorage token 并校验保存 |
| 手动粘贴 Cookie/JWT | 保留（网页登录兜底） | DriveLogin（保留原网络校验） |
| 迅雷短信验证码登录 | 桌面版接入 | sendSms→smsLogin→initCaptcha→exchangeToken 换 token 落库 |
| Notification/前台服务/WakeLock | 移除（in-app stats） | DownloadManager 桌面适配 |
| MediaStore/SAF 保存 | 文件系统 DesktopSaver | 支持目录结构相对路径（文件夹递归下载保持层级） |
| 云盘目录浏览页（各平台 CloudScreen） | desktop CloudDriveController + CloudDriveScreen | 复用 core 的 listCloudFiles/getDownloadLink，支持递归下载 |
| android.util.Base64/Log | JVM 兼容桩（core/android/util） | 保持移植代码原样 |
| ChunkDownloader/HlsDownloader/策略 | core 复用（Log 走桩） | 分片下载引擎原样保留 |
| DownloadManager | desktop 适配版 | Context/通知移除；其余逻辑（弹性分片、重试、限速、断点续传）保留 |

## 依赖与约束
- 迁移未引入 Kotlin/Compose 版本重写：Android 工程与桌面工程并存目录内，参考源在 E:\build\yunx-src\YunX-master。
- 跨平台：Compose Desktop 可在 Windows/macOS/Linux 运行；当前打包目标 Windows。
- 原应用内防二次打包的完整性自检（ArchiveProbe 等 Android 专属）在桌面版不适用、未移植。

## 上游同步记录

### 基线
- 原基线：上游 **edd29c1**（versionName 1.2.6，2026-09-04），逐文件比对 0 差异。
- 当前基线：上游 **6ac95bd**（versionName 1.2.7，2026-09-26）；未移植项见下表。

### 2026-09-30 同步（edd29c1 → 6ac95bd，上游 1.2.7）
| 上游提交 | 内容 | 桌面版处理 |
| --- | --- | --- |
| b0d2eb2 | fix: 大文件下载改为流式落盘（消除 ENOSPC 与幽灵任务） | **已移植**：`ChunkDownloader.mergeChunksToStream`（边写边删 + 逐块检查协程取消）；`DownloadManager.finishDownload` 改为直接流式写向最终文件；新增 `DesktopSaver.prepare`（只解析目标路径不复制）。峰值占用由 3 份降为 ≈ 文件大小 + 1 个分片。同时移植其单测 `ChunkDownloaderMergeTest` |
| 6ac95bd | Feature/update detection（Android：下载 APK 更新） | **已移植（桌面化）**：`app/UpdateChecker.kt` 查询本仓库 `releases/latest`，数字版本比较，安装包/镜像兜底链接；`ui/UpdateDialog.kt` 对话框；关于页「检查更新」+ 启动静默检查 + 「忽略此版本」（不自动替换自身） |
| f80c220 | 全量升级 Material 3 Expressive（主题令牌/组件/动效/首次引导） | **未移植**：Android Compose 主题体系，桌面版已有自己的配色派生（`ui/Theme.kt`，主题色/背景色自由调节） |
| edd29c1 及更早 | 反云注入自检 / 网盘列表分页搜索 | 自检为 Android 专属（不适用）；分页搜索属 Android 云盘页 UI，桌面版云盘页结构不同 |

### 与上游的有意差异（桌面版取舍）
- 上游「边写边删」后，失败/暂停时已消费的分片会丢失、恢复时重下该部分；桌面版沿用同一取舍，
  但额外在合并失败时**删除半成品目标文件**，避免留下看起来已完成的截断文件。
- 桌面版更新检测只提供「打开发布页 / 下载安装包 / 镜像下载」，不自动下载并替换自身。

