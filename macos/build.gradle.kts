import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "com.kimi.desktop"
version = "0.6.2"

// 版本号唯一来源是上面的 version；此任务把它写进资源 version.properties，代码从 classpath 读取
val generateVersionProperties = tasks.register("generateVersionProperties") {
    val outDir = layout.buildDirectory.dir("generated/versionResources")
    outputs.dir(outDir)
    doLast {
        val f = outDir.get().file("version.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=${project.version}\n")
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateVersionProperties)
    from(layout.buildDirectory.dir("generated/versionResources"))
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    testImplementation(kotlin("test"))
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.desktop.uiTestJUnit4)
}

compose.desktop {
    application {
        mainClass = "com.kimi.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Kimi Mobile"

            macOS {
                bundleID = "com.kimi.desktop"
                // macOS 包要求 MAJOR>0；语义版本 0.1 体现在最终 DMG 文件名上
                packageVersion = "1.0.16"
                iconFile.set(project.file("packaging/icon.icns"))
            }
        }
    }
}
