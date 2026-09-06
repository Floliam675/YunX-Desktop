pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 可选本地镜像（加快 jcef 原生库下载；普通克隆/CI 机器上没有该目录则自动跳过）
        val localMirror = System.getProperty("yunx.maven.mirror")?.takeIf { java.io.File(it).exists() }
        if (localMirror != null) {
            maven { url = uri(localMirror) }
        }
        mavenCentral()
        google()
    }
}
rootProject.name = "yunx-desktop"
include(":core", ":desktop")
