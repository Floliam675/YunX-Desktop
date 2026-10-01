# Third-party notices

YunX Desktop 复用了以下第三方组件/项目。各组件以各自许可证发布，全文以各自仓库/分发内 LICENSE 为准。

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| [CYQawa/YunX](https://github.com/CYQawa/YunX) | 本项目移植的上游（云析 Android 版） | [AGPL-3.0](LICENSE) |
| Microsoft Edge（用户本机自带，不属于本软件分发内容） | 网页登录（通过 DevTools Protocol 驱动，调试端口仅绑 127.0.0.1） | 随 Windows 分发，适用微软许可条款 |
| 上游应用图标（源自 CYQawa/YunX 的 `res/drawable/icon.png`，见 `packaging/icon.ico`、`desktop/src/main/resources/yunx_icon.png`） | 程序窗口/任务栏图标与 exe、安装器图标 | [AGPL-3.0](LICENSE) |
| Kotlin / Kotlinx（coroutines、datetime、atomicfu） | 语言与协程库 | Apache-2.0 |
| JetBrains Compose Multiplatform + Material3 | UI 框架 | Apache-2.0 |
| OkHttp / Okio | 网络与流 | Apache-2.0 |
| org.json | JSON 解析 | JSON License (permissive) |
| OpenJDK 运行时（打包内置，来自构建用 JDK：Azul Zulu 或 Eclipse Temurin） | 运行本程序所需 JRE，以及包内 `runtime\bin\java(w).exe` launcher（该 launcher 由 Java 厂商签名） | GPL-2.0-with-classpath-exception |
| Material Icons (core，随 Material3 引入；extended 集已移除) | 图标 | Apache-2.0 |
| [7-Zip](https://www.7-zip.org/) 的 SFX 自解压模块（内嵌在便携版 exe 中） | 便携版的自解压外壳 | LGPL-2.1+（含 unRAR 限制条款） |
| [Inno Setup](https://jrsoftware.org/isinfo.php)（仅构建期使用；生成的安装器/卸载器内嵌其运行时代码） | 生成安装版 exe | Inno Setup License（允许自由分发其生成的安装程序） |

分发说明：AGPL-3.0 全文见仓库根 [LICENSE](LICENSE)；本仓库不复制各组件许可全文，发行包内已随附/可从上述来源取得。
