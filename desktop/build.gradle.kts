import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.json:json:20250107")
    // 网页登录改为驱动本机已安装的 Microsoft Edge（DevTools Protocol，见 ui/EdgeCdpLogin.kt），
    // 不再内嵌 Chromium：旧 JCEF 方案占包体约 139 MB，用户首次运行还要解包 ~120 MB 内核。
    // CDP 走 JDK 自带 java.net.http —— 必须在 jlink 运行时里保留该模块（见 modules()）。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

compose.desktop {
    application {
        mainClass = (project.findProperty("mainClass") as String?) ?: "com.yunx.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "YunX Desktop"
            packageVersion = "0.4.7"
            // 网页登录用 JDK 的 java.net.http 连本机 Edge 的调试端口；jlink 默认不含该模块，必须显式保留
            modules("java.net.http")
            // exe 元数据（文件属性 → 详细信息 可见）；注意：这不等于代码签名，
            // Windows SmartScreen / 智能应用控制仍会按"未签名"处理。
            // 注意：jpackage 在 Windows 下对非 ASCII 元数据会报 "Input length = 1"，这里保持纯英文
            description = "YunX Desktop - network drive share parser and downloader (AGPL-3.0)"
            vendor = "Floliam675 (github.com/Floliam675/YunX-Desktop)"
            copyright = "Copyright (C) 2026 Floliam675 - AGPL-3.0 - upstream CYQawa/YunX"
            windows {
                // 原项目 YunX 的 launcher 图标（AGPL-3.0）转成的多尺寸 ico：exe 与安装器都用它
                iconFile.set(rootProject.file("packaging/icon.ico"))
                menuGroup = "YunX Desktop"
                shortcut = false
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "9f1c7a52-6f4b-4e2a-9c31-8b0d5e7a2f64"
            }
        }
    }
}
