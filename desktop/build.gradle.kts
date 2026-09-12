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
    // JCEF (Chromium) 网页登录
    implementation("me.friwi:jcefmaven:135.0.20")
    // JCEF 原生库（windows-amd64），打入运行时 classpath，首次运行无需联网下载
    runtimeOnly("me.friwi:jcef-natives-windows-amd64:jcef-ca49ada+cef-135.0.20+ge7de5c3+chromium-135.0.7049.85")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

compose.desktop {
    application {
        mainClass = (project.findProperty("mainClass") as String?) ?: "com.yunx.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "YunX Desktop"
            packageVersion = "0.2.0"
            // exe 元数据（文件属性 → 详细信息 可见）；注意：这不等于代码签名，
            // Windows SmartScreen / 智能应用控制仍会按"未签名"处理。
            // 注意：jpackage 在 Windows 下对非 ASCII 元数据会报 "Input length = 1"，这里保持纯英文
            description = "YunX Desktop - network drive share parser and downloader (AGPL-3.0)"
            vendor = "Floliam675 (github.com/Floliam675/YunX-Desktop)"
            copyright = "Copyright (C) 2026 Floliam675 - AGPL-3.0 - upstream CYQawa/YunX"
            windows {
                menuGroup = "YunX Desktop"
                shortcut = false
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "9f1c7a52-6f4b-4e2a-9c31-8b0d5e7a2f64"
            }
        }
    }
}
