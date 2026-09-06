plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.json:json:20250107")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation("junit:junit:4.13.2")
}
