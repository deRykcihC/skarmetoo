import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "com.deryk.skarmetoo"
version = "1.0.0"

repositories {
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.deryk.skarmetoo.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            modules("java.net.http", "jdk.crypto.ec")
            packageName = "SkarmetooDesktop"
            packageVersion = "1.0.0"
            description = "Skarmetoo Desktop — LM Studio bridge"
            vendor = "Deryk"
            windows {
                iconFile.set(project.file("src/main/resources/images/app_logo.png"))
                menuGroup = "Skarmetoo"
                shortcut = true
                perUserInstall = true
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
