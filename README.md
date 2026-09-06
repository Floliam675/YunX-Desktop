# YunX Desktop（云析桌面版）

YunX（云析）是一款 Android 网盘分享链接解析与高速下载应用（[github.com/CYQawa/YunX](https://github.com/CYQawa/YunX)，AGPL-3.0）。
**YunX Desktop 是它的 Windows 桌面移植版**：采用 Compose Multiplatform Desktop(JVM) + Kotlin，
最大化复用原项目的纯 Kotlin 业务逻辑（六家网盘的网络协议、分享解析、分片并发下载引擎）。[由Deepseek flash v4 0731完成]

## 功能
- 分享链接解析：夸克 / UC / 迅雷 / 百度 / 139 / 123 分享链接一键识别（含提取码）
- 文件浏览：解析后浏览分享目录，进入子文件夹，点击文件取直链下载
- 高速下载：Range 分片并发 + 断点续传 + 速度自适应弹性分片 + 单流回退 + HLS(m3u8) 下载
- 下载管理：暂停 / 继续 / 删除 / 失败自动重试 / 全局限速 / 多任务队列
- 云盘浏览：登录账号后浏览个人网盘（根/子目录、面包屑），单文件下载、**文件夹递归下载（保持目录结构）**
- 网盘登录：三种方式——① 手动粘贴 Cookie / JWT；② **迅雷短信验证码登录**；③ **网页登录**（内嵌 Chromium/JCEF，官方网页登录后自动抓取登录态，含 HttpOnly Cookie / localStorage token），本地持久化

## 技术栈 / 架构
```
yunx-desktop/
├─ core/     纯 JVM 业务逻辑（从原项目移植）：网络 API ×6、分享解析、分片/HLS 下载器、
│            Range/路径策略、android.util(Base64/Log) JVM 兼容桩
└─ desktop/  Compose Multiplatform Desktop UI：解析页 / 下载页 / 云盘 / 网盘账号 / 设置 +
             CloudDriveController（云盘浏览/递归下载）+ 内嵌 Chromium(JCEF, windowed) 网页登录
```
- Kotlin 2.2 + Compose Multiplatform 1.8.2 + Material3
- OkHttp（网络 + 分片下载）、org.json
- 持久化：JSON 文件（账号 / 下载任务 / 设置）+ AES-GCM 密钥文件（凭据），替代 Room

## 构建与运行
要求：JDK 17+。
```
gradlew :desktop:run            # 直接运行
gradlew :desktop:createDistributable    # 生成免安装目录（app-image）
gradlew :core:test                        # 单元测试
# 免安装发布：把 desktop/build/compose/binaries/main/app/"YunX Desktop" 目录压成 zip 即可分发
```
应用数据保存在 `%USERPROFILE%\.yunx-desktop`（账号、任务、设置），下载默认到 `Downloads`。
JCEF 首次运行会把 Chromium 内核解包到 `%USERPROFILE%\.jcef-bundle`（几秒，无需联网）。

## 登录方式（桌面版）
每平台提供「网页登录」按钮：点击后在应用内打开官方网页版登录
（**内嵌 Chromium / JCEF，windowed 模式**），用户完成登录后**自动检测并保存**：
- 夸克 / UC / 百度 / 139：抓取登录后浏览器实际发送的 Cookie（经请求/响应网络层捕获，
  含 HttpOnly 字段，如百度 BDUSS），命中平台判定条件即保存并关闭窗口；
- 123 云盘：自动读取网页 localStorage 中的 authorToken(JWT)。
也支持手动兜底：在「网盘账号」页粘贴 Cookie / JWT。迅雷额外支持**短信验证码登录**
（手机号→验证码→自动换 token）。
> 说明：账号数据仅保存在本机（见上方数据目录），不上传任何服务器。

## 下载
- Windows 免安装版（zip）：在右侧 Releases 下载 YunX-Desktop-<版本>-win64-portable.zip，
  解压后双击 YunX Desktop/YunX Desktop.exe 即可运行，无需安装、无需 Java。
- 建议核对 Release 附带的 SHA256 校验值；未签名 exe 首次运行可能被 SmartScreen/杀软提示，属正常现象。

## 免责声明与许可
- 仅供个人学习与技术交流；请遵守各网盘平台的服务条款。网盘协议基于抓包分析，可能随官方调整失效。
- 本项目基于 GNU AGPL-3.0 开源，移植自 CYQawa/YunX（AGPL-3.0），版权与许可见 [LICENSE](./LICENSE)；
  第三方组件许可见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)。
