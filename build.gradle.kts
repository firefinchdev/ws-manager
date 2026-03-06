plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val versionFile = layout.projectDirectory.file("version.properties")

fun getWsVersion(): String {
    return providers.fileContents(versionFile).asText.orNull?.lineSequence()
        ?.firstOrNull { it.startsWith("version=") }
        ?.substringAfter("version=")
        ?.trim()
        ?: "1.0.0"
}

group = "com.wsmanager"
version = getWsVersion()

repositories {
    mavenCentral()
}

tasks.register("generateBuildConfig") {
    val outputDir = layout.buildDirectory.dir("generated/src/nativeMain/kotlin/wsmanager")
    inputs.file(versionFile)
    outputs.dir(outputDir)
    doLast {
        
        val fields = mapOf("VERSION" to version)
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val fieldsText = fields.entries.joinToString("\n") { (key, value) ->
            "    const val $key = \"$value\""
        }
        File(dir, "BuildConfig.kt").writeText(
            """package wsmanager
              |
              |object BuildConfig {
              |$fieldsText
              |}
              """.trimMargin()
        )
    }
}

kotlin {
    val hostOs = System.getProperty("os.name")
    val isArm64 = System.getProperty("os.arch") == "aarch64"
    val nativeTarget = when {
        hostOs == "Mac OS X" && isArm64 -> macosArm64("native")
        hostOs == "Linux" && isArm64 -> linuxArm64("native")
        hostOs == "Linux" && !isArm64 -> linuxX64("native")
        hostOs.startsWith("Windows") -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    sourceSets {
        val nativeMain by getting {
            kotlin.srcDir(layout.buildDirectory.dir("generated/src/nativeMain/kotlin"))
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val nativeTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }

    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "wsmanager.main"
                baseName = "ws"
            }
        }
        compilations["main"].compileTaskProvider.configure {
            dependsOn("generateBuildConfig")
        }
    }
}

// ── Post-link strip task ────────────────────────────────────────────────────
// Strips the release binary of residual symbol-table entries that Kotlin/Native
// does not remove on its own.  On macOS `strip -Sx` removes debug info and local
// symbols while keeping the global symbol table intact (required by dyld).
// On Linux `strip -s` removes everything safely from a self-contained executable.
val stripReleaseExecutable by tasks.registering(Exec::class) {
    val hostOs = System.getProperty("os.name")
    val binaryName = "ws.kexe"
    val binaryPath = layout.buildDirectory
        .file("bin/native/releaseExecutable/$binaryName")
        .get().asFile

    dependsOn("linkReleaseExecutableNative")

    val stripArgs: List<String> = when {
        hostOs == "Mac OS X"        -> listOf("strip", "-Sx", binaryPath.absolutePath)
        hostOs.startsWith("Linux")  -> listOf("strip", "-s",  binaryPath.absolutePath)
        else                        -> listOf("strip",        binaryPath.absolutePath)
    }
    commandLine(stripArgs)

    doFirst {
        if (!binaryPath.exists()) {
            throw GradleException("Release binary not found at $binaryPath — run linkReleaseExecutableNative first")
        }
    }
}

// Wire strip into the standard nativeBinaries lifecycle task
tasks.named("nativeBinaries") {
    finalizedBy(stripReleaseExecutable)
}
