# YunX Desktop（云析桌面版）

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](./LICENSE)
[![Platform: Windows 10/11 x64](https://img.shields.io/badge/platform-Windows%2010%2F11%20x64-lightgrey)](#下载与安装)

> 项目主页：<https://github.com/Floliam675/YunX-Desktop> · 下载：<https://github.com/Floliam675/YunX-Desktop/releases>

YunX（云析）是一款 Android 网盘分享链接解析与高速下载应用（[CYQawa/YunX](https://github.com/CYQawa/YunX)，AGPL-3.0）。
**YunX Desktop 是它的 Windows 桌面移植版**：界面用 Kotlin + Compose Multiplatform Desktop 重写，
最大化复用上游的纯 Kotlin 业务逻辑（六家网盘的网络协议、分享解析、分片并发下载引擎）。

## 目录

- [功能特性](#功能特性)
- [下载与安装](#下载与安装)
- [常见问题：打不开或被拦截](#常见问题打不开或被拦截)
- [登录方式](#登录方式)
- [数据与隐私](#数据与隐私)
- [项目结构](#项目结构)
- [AI 生成声明](#ai-生成声明)
- [免责声明与许可](#免责声明与许可)

## 功能特性

**解析与下载**

- 分享链接解析：夸克 / UC / 迅雷 / 百度 / 139 / 123 分享链接一键识别（含提取码）；可直接
  **粘贴整段分享文案**（自动拆出链接与提取码，边输边提示识别结果），也可从剪贴板一键粘贴
- 文件浏览：解析后浏览分享目录、进入子文件夹，点击文件即取直链下载
- 高速下载：Range 分片并发 + 断点续传 + 速度自适应弹性分片 + 单流回退 + HLS(m3u8) 下载
- 下载管理：暂停 / 继续 / 删除 / 失败自动重试 / 全局限速 / 多任务队列

**云盘与账号**

- 云盘浏览：登录账号后浏览个人网盘（根/子目录、面包屑），单文件下载、**文件夹递归下载（保持目录结构）**
- 网盘登录三种方式：① 手动粘贴 Cookie / JWT；② **迅雷短信验证码登录**；③ **网页登录**（借用本机 Edge，
  登录后自动抓取登录态，含 HttpOnly Cookie / localStorage token），凭据本地持久化

**界面**

- Material3 侧边导航 + 页面切换滑动淡入动画
- 外观主题：跟随系统 / 浅色 / 深色，设置页即时切换并记忆
- 记忆上次所在页面与窗口尺寸；窗口尺寸超出当前屏幕时自动收敛，避免窗口跑到屏幕外
- 【关于】页展示版本、开源来源与许可、AI 生成声明、数据目录

## 下载与安装

到 [Releases](https://github.com/Floliam675/YunX-Desktop/releases) 下载（两种发行版内容完全相同，任选其一）：

| 版本 | 文件 | 说明 |
| --- | --- | --- |
| **安装版**（推荐） | `YunX-Desktop-<版本>-win64-installer.exe` | 双击安装：可选安装目录，默认按用户装到 `%LOCALAPPDATA%\YunX Desktop`，在开始菜单创建快捷方式，可从「应用和功能」正常卸载。静默安装：加 `/quiet`（如 `...installer.exe /quiet`）。安装更高版本会原地升级。 |
| 免安装版 | `YunX-Desktop-<版本>-win64-portable.zip` | 解压后双击 `YunX Desktop\YunX Desktop.exe` 即可运行；不写注册表、不留安装痕迹。 |

**系统要求**：Windows 10 / 11（x64）。两种版本都**内置 Java 运行时，无需另行安装 Java**。

**首次启动**：无需解包任何内核；点「网页登录」时会调用本机已安装的 Microsoft Edge（Win10/11 自带），不额外下载浏览器。

- 若系统提示被拦截（尤其“智能应用控制”），两种版本都可改用目录内的 **Start-YunX.bat**：它用自带、由 Java
  厂商数字签名的 java launcher 启动同一程序，**无需安装 Java**，详见下方常见问题。
- 建议核对 Release 附带的 `SHA256.txt` 校验值；未签名程序首次运行可能被 SmartScreen/杀软提示，属正常现象。

## 常见问题：打不开或被拦截

本程序**没有数字签名（代码签名证书）**，因此 Windows 可能拦截。这不是病毒，但需要你知情后选择处理方式：

| 提示 | 能否继续运行 | 处理办法 |
| --- | --- | --- |
| **SmartScreen**：「Windows 已保护你的电脑」 | 可以 | 点「更多信息」→「仍要运行」 |
| **智能应用控制 Smart App Control**：「已阻止可能不安全的应用」 | **不行**（无“仍要运行”按钮） | 见下方三种办法 |

智能应用控制（Windows 11）只放行**已签名且具有微软信誉**的程序，它**不看** exe 里的“发布者/公司”元数据——所以把发布者写成 GitHub 并不能绕过它。可选：

1. **双击 `Start-YunX.bat`**（推荐、零安装）：便携包内／安装目录内自带 JRE，并附带由 Java 厂商（Azul Zulu / Eclipse Temurin）**数字签名**的 `runtime\bin\javaw.exe`，脚本用它启动同一程序。被执行的是已签名程序，智能应用控制一般会放行，用户**不需要安装任何东西**（也无需关闭该功能）。
2. **自己关闭智能应用控制**：设置 → 隐私和安全性 → Windows 安全中心 → 应用和浏览器控制 → 智能应用控制 → 关闭。⚠️ 关闭后**需重装系统才能再次开启**，请自行评估；公司/学校电脑通常无权限修改。
3. **等待签名版**：见下方「关于代码签名」。

无论用哪种方式，建议先用发布页的 `SHA256.txt` 校验下载文件完整性。

## 登录方式

每平台提供「网页登录」按钮：点击后在应用内打开官方网页版登录
（**借用本机已安装的 Edge，经 DevTools Protocol 驱动**），用户完成登录后**自动检测并保存**：

- 夸克 / UC / 百度 / 139：抓取登录后浏览器实际发送的 Cookie（经请求/响应网络层捕获，
  含 HttpOnly 字段，如百度 BDUSS），命中平台判定条件即保存并关闭窗口；
- 123 云盘：自动读取网页 localStorage 中的 authorToken(JWT)。

也支持手动兜底：在「网盘账号」页粘贴 Cookie / JWT。迅雷额外支持**短信验证码登录**
（手机号→验证码→自动换 token）。

> 说明：账号数据仅保存在本机（见下方数据目录），不上传任何服务器。

## 数据与隐私

| 内容 | 位置 |
| --- | --- |
| 账号凭据（AES-GCM 加密）· 下载任务 · 设置 | `%USERPROFILE%\.yunx-desktop` |
| 网页登录用的 Edge 配置目录（仅本机可访问，缓存已封顶） | `%USERPROFILE%\.yunx-desktop\edge-login-profile` |
| 下载的文件 | 系统 `Downloads` 目录 |

- 所有数据仅保存在本机，不上传任何服务器；网盘凭据只用于直连对应网盘官方接口。
- 卸载程序只会移除安装目录，**不会**自动删除上面两个用户目录；如需彻底清除请手动删除。

## 项目结构

```
yunx-desktop/
├─ core/     纯 JVM 业务逻辑（从原项目移植）：网络 API ×6、分享解析、分片/HLS 下载器、
│            Range/路径策略、android.util(Base64/Log) JVM 兼容桩
└─ desktop/  Compose Multiplatform Desktop UI：解析页 / 下载页 / 云盘 / 网盘账号 / 设置 +
             CloudDriveController（云盘浏览/递归下载）+ 借用本机 Edge(DevTools Protocol) 网页登录
```

- Kotlin 2.2 + Compose Multiplatform 1.8.2 + Material3
- OkHttp（网络 + 分片下载）、org.json
- 持久化：JSON 文件（账号 / 下载任务 / 设置）+ AES-GCM 密钥文件（凭据），替代 Room

## AI 生成声明

本项目的**桌面移植工程**（YunX Desktop）由维护者提出需求并负责验证，在 **AI 编码助手 DeepSeek v4 Flash（deepseek-v4-flash）** 的辅助下开发：

- 工程搭建、Compose Multiplatform 界面、解析/下载/云盘/账号流程整合、网页登录（借用本机 Edge / CDP）
  实现、Windows 打包与大量调试修复工作，主要由 AI 生成候选代码，经维护者审查、测试验证后合入；
  维护者负责需求定义、测试、打包与发布。
- **上游归属不受影响**：本项目复用的网盘协议、分享解析与下载引擎逻辑源自
  [CYQawa/YunX](https://github.com/CYQawa/YunX)（Android 版，AGPL-3.0），
  其版权与署名归原作者所有，详见 [PORTING.md](./PORTING.md)；本声明不覆盖、不减损上游作者的权利。
- **许可一致**：AI 生成或辅助修改的代码与全仓库一致，均以 GNU AGPL-3.0 发布，不额外主张版权；
  任何修改与再分发请遵守 [LICENSE](./LICENSE)。
- **质量与责任**：AI 生成代码可能含有缺陷或安全隐患，欢迎通过 Issues / Pull Requests 审查指正；
  使用者请自行评估并承担风险（另见下方免责声明）。
- **所用 AI**：DeepSeek v4 Flash（deepseek-v4-flash）——DeepSeek 出品的大语言模型，用于代码生成、重构与排错。

## 免责声明与许可

- 仅供个人学习与技术交流；请遵守各网盘平台的服务条款。网盘协议基于抓包分析，可能随官方调整失效。
- 本项目基于 GNU AGPL-3.0 开源，移植自 CYQawa/YunX（AGPL-3.0），版权与许可见 [LICENSE](./LICENSE)；
  第三方组件许可见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)。
- 本项目为**非官方**移植版，与上游作者无关。
