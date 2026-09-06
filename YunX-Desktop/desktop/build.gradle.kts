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
    implementation(compose.material)
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.json:json:20250107")
    // JCEF (Chromium) 网页登录
    implementation("me.friwi:jcefmaven:135.0.20")
    // JCEF 原生库（windows-amd64），打入运行时 classpath，首次运行无需联网下载
    runtimeOnly("me.friwi:jcef-natives-windows-amd64:jcef-ca49ada+cef-135.0.20+ge7de5c3+chromium-135.0.7049.85")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // 网页登录（还原原版 WebView）：内嵌 JavaFX WebView（含各模块与 Windows 原生）
    implementation("org.openjfx:javafx-base:17.0.14:win")
    implementation("org.openjfx:javafx-graphics:17.0.14:win")
    implementation("org.openjfx:javafx-controls:17.0.14:win")
    implementation("org.openjfx:javafx-media:17.0.14:win")
    implementation("org.openjfx:javafx-web:17.0.14:win")
    implementation("org.openjfx:javafx-swing:17.0.14:win")
}

compose.desktop {
    application {
        mainClass = (project.findProperty("mainClass") as String?) ?: "com.yunx.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "YunX Desktop"
            packageVersion = "0.1.0"
        }
    }
}
