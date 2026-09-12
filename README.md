# YunX Desktop（云析桌面版）

> 项目主页：<https://github.com/Floliam675/YunX-Desktop> · Releases：<https://github.com/Floliam675/YunX-Desktop/releases>

YunX（云析）是一款 Android 网盘分享链接解析与高速下载应用（[github.com/CYQawa/YunX](https://github.com/CYQawa/YunX)，AGPL-3.0）。
**YunX Desktop 是它的 Windows 桌面移植版**：采用 Compose Multiplatform Desktop(JVM) + Kotlin，
最大化复用原项目的纯 Kotlin 业务逻辑（六家网盘的网络协议、分享解析、分片并发下载引擎）。

## 功能
- 分享链接解析：夸克 / UC / 迅雷 / 百度 / 139 / 123 分享链接一键识别（含提取码）
- 文件浏览：解析后浏览分享目录，进入子文件夹，点击文件取直链下载
- 高速下载：Range 分片并发 + 断点续传 + 速度自适应弹性分片 + 单流回退 + HLS(m3u8) 下载
- 下载管理：暂停 / 继续 / 删除 / 失败自动重试 / 全局限速 / 多任务队列
- 界面体验：Material3 侧边导航 + 页面切换滑动淡入动画；【关于】页展示版本、开源来源与许可、AI 生成声明、数据目录
- 云盘浏览：登录账号后浏览个人网盘（根/子目录、面包屑），单文件下载、**文件夹递归下载（保持目录结构）**
- 网盘登录：三种方式——① 手动粘贴 Cookie / JWT；② **迅雷短信验证码登录**；③ **网页登录**（内嵌 Chromium/JCEF，登录后自动抓取登录态，含 HttpOnly Cookie / localStorage token），本地持久化

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
gradlew :desktop:run                    # 直接运行
gradlew :desktop:createDistributable    # 生成免安装目录（app-image）
gradlew :core:test                      # 单元测试
# 免安装发布：把 desktop/build/compose/binaries/main/app/"YunX Desktop" 目录压成 zip 即可分发
# 可选瘦身：tools/trim-jcef-locales.ps1 裁剪 Chromium 语言包（natives jar 134MB -> 123MB）
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
- Windows 免安装版（zip）：在 [Releases](https://github.com/Floliam675/YunX-Desktop/releases) 下载 YunX-Desktop-<版本>-win64-portable.zip，解压后双击 YunX Desktop/YunX Desktop.exe 即可运行，无需安装、无需 Java。
- 若系统提示被拦截（尤其“智能应用控制”），直接双击包内的 **Start-YunX.bat**：它用包内自带的、由 Java 厂商数字签名的 java launcher 启动，**无需安装 Java**，详见下方常见问题。
- 建议核对 Release 附带的 SHA256 校验值；未签名 exe 首次运行可能被 SmartScreen/杀软提示，属正常现象。


## 常见问题：Windows 打不开 / 提示不安全

本程序**没有数字签名（代码签名证书）**，因此 Windows 可能拦截。这不是病毒，但需要你知情后选择处理方式：

| 提示 | 能否继续运行 | 处理办法 |
| --- | --- | --- |
| **SmartScreen**：「Windows 已保护你的电脑」 | 可以 | 点「更多信息」→「仍要运行」 |
| **智能应用控制 Smart App Control**：「已阻止可能不安全的应用」 | **不行**（无“仍要运行”按钮） | 见下方三种办法 |

智能应用控制（Windows 11）只放行**已签名且具有微软信誉**的程序，它**不看** exe 里的“发布者/公司”元数据——所以把发布者写成 GitHub 并不能绕过它。可选：

1. **双击包内的 `Start-YunX.bat`**（推荐、零安装）：便携包内自带 JRE，并附带由 Java 厂商（Azul Zulu / Eclipse Temurin）**数字签名**的 `runtime\bin\javaw.exe`，脚本用它启动同一程序。被执行的是已签名程序，智能应用控制一般会放行，用户**不需要安装任何东西**（也无需关闭该功能）。
2. **自己关闭智能应用控制**：设置 → 隐私和安全性 → Windows 安全中心 → 应用和浏览器控制 → 智能应用控制 → 关闭。⚠️ 关闭后**需重装系统才能再次开启**，请自行评估；公司/学校电脑通常无权限修改。
3. **等待签名版**：见下方“关于代码签名”。

无论用哪种方式，建议先用发布页的 `SHA256.txt` 校验下载文件完整性。

### 关于代码签名（根治方案）

要让所有 Windows 机器直接双击运行、且 SmartScreen/智能应用控制不再拦截，只能做 Authenticode 代码签名：

- **开源免费**：[SignPath Foundation](https://signpath.org/) 为开源项目提供免费代码签名，可与 GitHub Actions 集成（需申请、项目需符合其开源政策）。
- **付费证书**：OV 证书（约 200–400 美元/年，SmartScreen 需逐步累积信誉）、EV 证书（约 300–600 美元/年，可较快建立信誉；现多为云签名/硬件令牌形式）。
- **微软云签名**：[Azure Trusted Signing](https://azure.microsoft.com/products/trusted-signing) 价格较低，但对个人开发者有资质门槛（通常要求 3 年企业历史）。
- 免费/自签名证书**不能**解决问题：SmartScreen 与智能应用控制不信任自签证书。

> 说明：exe 的「文件属性 → 详细信息」里的公司/版权信息（当前为 `Floliam675 (github.com/Floliam675/YunX-Desktop)`）只是元数据展示，与上述拦截判定无关。


## 持续集成与自动发布（含可选的代码签名）

仓库自带两个 GitHub Actions 工作流（`.github/workflows/`）：

- **`ci.yml`**：push / PR 时编译 `:desktop` 并跑 `:core:test`。
- **`release.yml`**：推送 `v*` 标签（或手动触发）时自动：生成免安装 app-image → 裁剪 Chromium 语言包 → 放入已签名的 JRE launcher 与 `Start-YunX.bat` →
  注入 `LICENSE.txt` / `SOURCE.txt` / `README.txt` → 打出便携 zip →
  计算 `SHA256.txt` →（可选签名）→ 创建 GitHub Release（默认草稿）。

发布一个新版本：

```
git tag v0.2.0
git push origin v0.2.0
```

### 启用免费代码签名（SignPath，开源项目）

1. 到 [signpath.org](https://signpath.org/) 申请开源项目签名，拿到 organization id、project slug、
   signing policy slug、artifact configuration slug 与 API Token；
2. 仓库 Settings → Secrets and variables → Actions 中配置：
   - **Variables**：`SIGNPATH_ENABLED=true`、`SIGNPATH_ORG_ID`、`SIGNPATH_PROJECT_SLUG`、
     `SIGNPATH_POLICY_SLUG`、`SIGNPATH_ARTIFACT_CONFIG_SLUG`；
   - **Secrets**：`SIGNPATH_API_TOKEN`；
3. 之后每次打标签，`sign` 任务会把未签名工件提交给 SignPath 并取回签名后的 zip；未配置这些变量时
   签名任务自动跳过，仍产出未签名包。

> 工件配置示例见 `packaging/signpath-artifact-configuration.xml`（对 zip 内的 `YunX Desktop.exe` 做
> Authenticode 签名）；实际以 SignPath 后台生成的配置为准。
> 说明：免费/自签名证书无法解决 SmartScreen 与智能应用控制（Smart App Control）的拦截，必须使用
> 受信任 CA 签发的证书（SignPath/OV/EV 或 Azure Trusted Signing）。

## AI 生成声明（AI Disclosure）

本项目的**桌面移植工程**（YunX Desktop）由维护者提出需求并负责验证，在 **AI 编码助手 DeepSeek v4 Flash（deepseek-v4-flash）** 的辅助下开发：

- 工程搭建、Compose Multiplatform 界面、解析/下载/云盘/账号流程整合、网页登录（内嵌 Chromium/JCEF）
  实现、Windows 打包与大量调试修复工作，主要由 AI 生成候选代码，经维护者审查、测试验证后合入；
  维护者负责需求定义、测试、打包与发布。
- **上游归属不受影响**：本项目复用的网盘协议、分享解析与下载引擎逻辑源自
  [CYQawa/YunX](https://github.com/CYQawa/YunX)（Android 版，AGPL-3.0，人类作者），
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
