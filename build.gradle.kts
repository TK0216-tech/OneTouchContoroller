import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"   // must be the same version as Kotlin
    id("org.jetbrains.compose") version "1.9.3"
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Jetpack Compose for Desktop (the window)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // Ktor server (same as before)
    implementation("io.ktor:ktor-server-core-jvm:3.0.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.3")
    implementation("io.ktor:ktor-server-websockets-jvm:3.0.3")   // fast live connection for the touchpad
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("com.google.zxing:core:3.5.3")   // draws the QR code for the web version
    implementation("com.github.kwhat:jnativehook:2.2.2")   // hears the mouse and keyboard while a routine is recorded
    implementation("net.java.dev.jna:jna:5.15.0")            // Guard mode: talks to Windows (block mouse and keyboard)
    implementation("net.java.dev.jna:jna-platform:5.15.0")

    // Tests (never included in the packaged application)
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.0.3")
    testImplementation("io.ktor:ktor-client-websockets-jvm:3.0.3")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "jp.relative.tech.one_touch_controller.MainKt"

        nativeDistributions {
            // Exe = installer (needs WiX Toolset 3). The portable folder needs no extra tools.
            targetFormats(TargetFormat.Exe)
            packageName = "OneTouchController"
            packageVersion = "1.0.0"
            description = "PC server for the One Touch Controller phone app"
            vendor = "THANT SIN OO"

            // Java parts that the packaged program needs (Netty/Ktor use these)
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.sql", "java.logging", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icon.ico"))   // icon.ico must be in the project folder
                menuGroup = "One Touch Controller"
                shortcut = true
                dirChooser = true
                perUserInstall = true                    // installs without needing administrator rights
            }
        }
    }
}
