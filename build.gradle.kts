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

    // ── Target selection ────────────────────────────────────────────────────────
    // Default target for the current host (used for local dev builds).
    val defaultTarget = when {
        hostOs == "Mac OS X"          -> "macosArm64"
        hostOs.startsWith("Linux")    -> "linuxX64"
        hostOs.startsWith("Windows")  -> "mingwX64"
        else -> throw GradleException("Host OS '$hostOs' is not supported.")
    }

    // Pass -Ptarget=<name> to cross-compile from the current host:
    //   macOS ARM  host  →  -Ptarget=macosX64     (produces Intel macOS binary)
    //   Linux x64  host  →  -Ptarget=linuxArm64   (produces ARM64 Linux binary)
    val targetName = (project.findProperty("target") as String?) ?: defaultTarget

    val nativeTarget = when (targetName) {
        "macosArm64" -> macosArm64("native")
        "macosX64"   -> macosX64("native")
        "linuxX64"   -> linuxX64("native")
        "linuxArm64" -> linuxArm64("native")
        "mingwX64"   -> mingwX64("native")
        else -> throw GradleException(
            "Unknown -Ptarget '$targetName'. Valid: macosArm64, macosX64, linuxX64, linuxArm64, mingwX64"
        )
    }

    // ── Platform source directory ───────────────────────────────────────────────
    // Selected by TARGET (not host) so cross-compilation picks the right APIs:
    //   macOS target  → Darwin Ktor engine + macOS PlatformUtils
    //   Linux target  → CIO   Ktor engine + Linux  PlatformUtils
    //   Windows target→ WinHttp             + Windows PlatformUtils
    val platformSrcDir = when {
        targetName.startsWith("macos")  -> "src/platform/macos/kotlin"
        targetName.startsWith("linux")  -> "src/platform/linux/kotlin"
        targetName.startsWith("mingw")  -> "src/platform/windows/kotlin"
        else -> throw GradleException("No platform source dir for target: $targetName")
    }

    sourceSets {
        val nativeMain by getting {
            kotlin.srcDir(layout.buildDirectory.dir("generated/src/nativeMain/kotlin"))
            kotlin.srcDir(platformSrcDir)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                // Ktor core — platform-agnostic HTTP client API
                implementation(libs.ktor.client.core)
                // Platform-specific Ktor engine — chosen by target, not host OS
                when {
                    targetName.startsWith("macos")  -> implementation(libs.ktor.client.darwin)
                    targetName.startsWith("linux")  -> implementation(libs.ktor.client.cio)
                    targetName.startsWith("mingw")  -> implementation(libs.ktor.client.winhttp)
                }
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
    val hostOs    = System.getProperty("os.name")
    val defaultT  = when {
        hostOs == "Mac OS X"         -> "macosArm64"
        hostOs.startsWith("Linux")   -> "linuxX64"
        hostOs.startsWith("Windows") -> "mingwX64"
        else -> "unknown"
    }
    val targetName = (project.findProperty("target") as String?) ?: defaultT

    // Kotlin/Native produces .exe on Windows/MinGW, .kexe everywhere else
    val binaryName = if (targetName == "mingwX64") "ws.exe" else "ws.kexe"
    val binaryPath = layout.buildDirectory
        .file("bin/native/releaseExecutable/$binaryName")
        .get().asFile

    dependsOn("linkReleaseExecutableNative")

    val stripArgs: List<String> = when {
        targetName.startsWith("macos")  -> listOf("strip", "-Sx", binaryPath.absolutePath)
        targetName.startsWith("linux")  -> listOf("strip", "-s",  binaryPath.absolutePath)
        else                            -> listOf("strip",        binaryPath.absolutePath)
    }
    commandLine(stripArgs)

    // strip may fail when cross-compiling (host strip is architecture-specific).
    // CI handles stripping in its own step; this task is best-effort locally.
    isIgnoreExitValue = true

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
