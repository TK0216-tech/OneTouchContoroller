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

dependencies {
    // Jetpack Compose for Desktop (the window)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // Ktor server (same as before)
    implementation("io.ktor:ktor-server-core-jvm:3.0.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.3")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("com.google.zxing:core:3.5.3")   // draws the QR code for the web version
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            // Exe = installer (needs WiX Toolset 3). The portable folder needs no extra tools.
            targetFormats(TargetFormat.Exe)
            packageName = "OneTouchController"
            packageVersion = "1.0.0"
            description = "PC server for the One Touch Controller phone app"
            vendor = "THANT SIN OO"

            // Java parts that the packaged program needs (Netty/Ktor use these)
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.sql", "jdk.unsupported")

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