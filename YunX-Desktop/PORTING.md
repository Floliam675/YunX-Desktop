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
