@file:OptIn(ExperimentalWasmDsl::class)

import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.vannitktech.maven.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlinCocoapods)
}

group = "io.github.kdroidfilter.composemediaplayer"

val ref = System.getenv("GITHUB_REF") ?: ""

// Version resolution, in order of precedence:
//   1. -PprojectVersion=<x.y.z>  — used by the manually dispatched GitHub Packages publish
//   2. a v-prefixed git tag       — used by the tag-triggered Maven Central publish
//   3. "dev"                      — local development builds
val projectVersion =
    providers.gradleProperty("projectVersion").orNull
        ?: if (ref.startsWith("refs/tags/")) {
            val tag = ref.removePrefix("refs/tags/")
            if (tag.startsWith("v")) tag.substring(1) else tag
        } else {
            "dev"
        }

kotlin {
    jvmToolchain(17)
    @Suppress("DEPRECATION")
    androidTarget { publishLibraryVariants("release") }
    jvm()
    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.compilations.getByName("main") {
            // The default file path is src/nativeInterop/cinterop/<interop-name>.def
            val nskeyvalueobserving by cinterops.creating
        }
    }

    cocoapods {
        version = if (projectVersion == "dev") "0.0.1-dev" else projectVersion
        summary = "A multiplatform video player library for Compose applications"
        homepage = "https://github.com/kdroidFilter/Compose-Media-Player"
        name = "ComposeMediaPlayer"

        framework {
            baseName = "ComposeMediaPlayer"
            isStatic = false
            @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
            transitiveExport = false
        }

        // Maps custom Xcode configuration to NativeBuildType
        xcodeConfigurationToNativeBuildType["CUSTOM_DEBUG"] = NativeBuildType.DEBUG
        xcodeConfigurationToNativeBuildType["CUSTOM_RELEASE"] = NativeBuildType.RELEASE
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            api(libs.filekit.core)
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.androidcontextprovider)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.database)
            implementation(libs.androidx.media3.ui)
            implementation(libs.androidx.activityCompose)
            implementation(libs.androidx.core)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
        }

        jvmTest.dependencies {
            runtimeOnly(compose.desktop.currentOs)
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.test)
        }

        if (Os.isFamily(Os.FAMILY_MAC)) {
            iosMain.dependencies {
            }

            iosTest.dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        webMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(libs.compose.ui)
        }

        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }

    // https://kotlinlang.org/docs/native-objc-interop.html#export-of-kdoc-comments-to-generated-objective-c-headers
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        compilations["main"].compileTaskProvider.configure {
            compilerOptions {
                freeCompilerArgs.add("-Xexport-kdoc")
            }
        }
    }
}

android {
    namespace = "io.github.kdroidfilter.composemediaplayer"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }
}

val nativeResourceDir = layout.projectDirectory.dir("src/jvmMain/resources/composemediaplayer/native")

val buildNativeMacOs by tasks.registering(Exec::class) {
    description = "Compiles the Swift native library into macOS dylibs (arm64 + x64)"
    group = "build"
    enabled = Os.isFamily(Os.FAMILY_MAC)

    val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/macos")
    inputs.dir(nativeDir)
    inputs.file(rootProject.file("tools/legacy_native_manifest.py"))
    outputs.dirs(nativeResourceDir.dir("darwin-aarch64"), nativeResourceDir.dir("darwin-x86-64"))
    workingDir(nativeDir)
    commandLine("bash", "build.sh")
}

val buildNativeWindows by tasks.registering(Exec::class) {
    description = "Compiles the C++ native library into Windows DLLs (x64 + ARM64)"
    group = "build"
    enabled = Os.isFamily(Os.FAMILY_WINDOWS)

    val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/windows")
    inputs.dir(nativeDir)
    inputs.file(rootProject.file("tools/legacy_native_manifest.py"))
    outputs.dirs(nativeResourceDir.dir("win32-arm64"), nativeResourceDir.dir("win32-x86-64"))
    workingDir(nativeDir)
    commandLine("cmd", "/c", nativeDir.file("build.bat").asFile.absolutePath)
}

val buildNativeLinux by tasks.registering(Exec::class) {
    description = "Compiles the C native library into Linux .so (GStreamer + JNI)"
    group = "build"
    enabled = Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC)

    val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/linux")
    inputs.dir(nativeDir)
    inputs.file(rootProject.file("tools/legacy_native_manifest.py"))
    outputs.dir(nativeResourceDir.dir(if (System.getProperty("os.arch").contains("aarch64")) "linux-aarch64" else "linux-x86-64"))
    workingDir(nativeDir)
    commandLine("bash", "build.sh")
}

tasks.withType<Test>().configureEach {
    if (name == "jvmTest") {
        inputs.property("nativePublicSamples", providers.environmentVariable("COMPOSE_MEDIA_PLAYER_RUN_PUBLIC_TEST").orElse("0"))
        val publicCdm = providers.environmentVariable("COMPOSE_MEDIA_PLAYER_WIDEVINE_CDM").orNull
        inputs.property("publicWidevineEnabled", publicCdm != null)
        if (publicCdm != null) inputs.file(publicCdm).withPropertyName("publicWidevineCdm")
        if (providers.environmentVariable("COMPOSE_MEDIA_PLAYER_RUN_PUBLIC_TEST").orNull == "1") {
            outputs.upToDateWhen { false }
            outputs.cacheIf("Public network tests must execute") { false }
        }
        val widevineConfig = providers.environmentVariable("COMPOSE_MEDIA_PLAYER_WIDEVINE_CONFIG").orNull
        inputs.property("nativeWidevineTestEnabled", widevineConfig != null)
        if (widevineConfig != null) {
            inputs.file(widevineConfig).withPropertyName("nativeWidevineTestConfiguration")
            outputs.cacheIf("Private Widevine integration tests are not cached") { false }
        }
        providers.environmentVariable("COMPOSE_MEDIA_PLAYER_RUNTIME_DIR").orNull?.let { systemProperty("composemediaplayer.runtimeDir", it) }
        inputs.property(
            "nativeDashTest",
            providers.environmentVariable("COMPOSE_MEDIA_PLAYER_RUN_DASH_TEST").orElse("0"),
        )
        inputs.property(
            "nativeMetadataStartupTest",
            providers.environmentVariable("COMPOSE_MEDIA_PLAYER_RUN_NATIVE_STARTUP_TEST").orElse("0"),
        )
    }
}

tasks.named("jvmProcessResources") {
    dependsOn(buildNativeMacOs, buildNativeWindows, buildNativeLinux)
}

tasks.configureEach {
    if (name == "sourcesJar") {
        dependsOn(buildNativeMacOs, buildNativeWindows, buildNativeLinux)
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.kdroidfilter",
        artifactId = "composemediaplayer",
        version = projectVersion,
    )

    pom {
        name.set("Compose Media Player")
        description.set("A multiplatform video player library for Compose applications.")
        inceptionYear.set("2025")
        url.set("https://github.com/kdroidFilter/Compose-Media-Player")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("kdroidfilter")
                name.set("Elyahou Hadass")
                email.set("elyahou.hadass@gmail.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/kdroidFilter/Compose-Media-Player.git")
            developerConnection.set("scm:git:ssh://git@github.com:kdroidFilter/Compose-Media-Player.git")
            url.set("https://github.com/kdroidFilter/Compose-Media-Player")
        }
    }

    publishToMavenCentral()

    // Only sign publications in CI environments to avoid requiring local GPG signing setup.
    // The key check also keeps the GitHub Packages publish working: that registry does not need
    // signatures and the workflow does not carry GPG secrets, so signing stays off there.
    if (System.getenv("CI") != null && System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null) {
        signAllPublications()
    }

}

/**
 * Secondary publication target: GitHub Packages on the fork.
 *
 * Publish with `./gradlew :mediaplayer:publishAllPublicationsToGitHubPackagesRepository`, which
 * targets only this repository and never Maven Central. Credentials come from the environment
 * (`GITHUB_ACTOR` / `GITHUB_TOKEN`, both provided automatically inside GitHub Actions) or from the
 * `gpr.user` / `gpr.key` Gradle properties for local runs.
 *
 * `githubPackagesUrl` can be overridden to publish into a local directory for verification, e.g.
 * `-PgithubPackagesUrl=file:///tmp/repo`, which exercises the whole publication without uploading.
 */
publishing {
    repositories {
        val githubPackagesUrl =
            providers.gradleProperty("githubPackagesUrl")
                .getOrElse("https://maven.pkg.github.com/itboy87/ComposeMediaPlayer")

        maven {
            name = "GitHubPackages"
            url = uri(githubPackagesUrl)

            // The `file:` protocol rejects any authentication scheme, so credentials are attached
            // only for real remote publishes.
            if (!githubPackagesUrl.startsWith("file:")) {
                credentials {
                    username =
                        providers.gradleProperty("gpr.user").orNull
                            ?: providers.environmentVariable("GITHUB_ACTOR").getOrElse("")
                    password =
                        providers.gradleProperty("gpr.key").orNull
                            ?: providers.environmentVariable("GITHUB_TOKEN").getOrElse("")
                }
            }
        }
    }
}

// Complete runtime resources are generated, never checked into source resources.
val mediaRuntimeResources = layout.buildDirectory.dir("generated/mediaResources")
val buildNativeMediaRuntime by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the pinned, relocatable DASH/ASS runtime for the host architecture"
    enabled = Os.isFamily(Os.FAMILY_MAC) || Os.isFamily(Os.FAMILY_WINDOWS)
    inputs.dir("src/jvmMain/native/shared")
    inputs.files(rootProject.file("tools/build_media_runtime.py"), rootProject.file("tools/download_native.py"))
    outputs.dir(mediaRuntimeResources)
    workingDir(rootProject.projectDir)
    commandLine(if (Os.isFamily(Os.FAMILY_WINDOWS)) "python" else "python3", "tools/build_media_runtime.py", "--output", mediaRuntimeResources.get().dir("composemediaplayer/runtime").asFile.absolutePath)
}
kotlin.sourceSets.named("jvmMain") { resources.srcDir(mediaRuntimeResources) }
tasks.named("jvmProcessResources") { dependsOn(buildNativeMediaRuntime) }
val extractMediaRuntime by tasks.registering(Copy::class) {
    description = "Stages native files for downstream application signing; sign after extraction"
    dependsOn(buildNativeMediaRuntime)
    from(mediaRuntimeResources)
    into(layout.buildDirectory.dir("applicationNativeResources"))
}

// CI prebuilt bundles are checked against the current checkout before resource packaging.
val verifyPrebuiltMediaRuntimes by tasks.registering(Exec::class) {
    dependsOn(buildNativeMediaRuntime)
    inputs.files(fileTree(nativeResourceDir.dir("runtime")))
    inputs.dir("src/jvmMain/native/shared")
    inputs.files(rootProject.file("tools/build_media_runtime.py"), rootProject.file("tools/verify_runtime_set.py"))
    workingDir(rootProject.projectDir)
    commandLine(if (Os.isFamily(Os.FAMILY_WINDOWS)) "python" else "python3", "tools/verify_runtime_set.py", nativeResourceDir.dir("runtime").asFile.absolutePath, mediaRuntimeResources.get().dir("composemediaplayer/runtime").asFile.absolutePath)
}
val hostArm = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
val hostRuntimePlatform = when {
    Os.isFamily(Os.FAMILY_MAC) -> if (hostArm) "darwin-aarch64" else "darwin-x86-64"
    Os.isFamily(Os.FAMILY_WINDOWS) -> if (hostArm) "win32-arm64" else "win32-x86-64"
    else -> "linux"
}
tasks.named<ProcessResources>("jvmProcessResources") {
    dependsOn(verifyPrebuiltMediaRuntimes)
    exclude("composemediaplayer/native/runtime/**")
    from(nativeResourceDir.dir("runtime")) {
        exclude("$hostRuntimePlatform/**")
        into("composemediaplayer/runtime")
    }
}
val verifyPublicationMediaRuntimes by tasks.registering(Exec::class) {
    dependsOn(buildNativeMediaRuntime, verifyPrebuiltMediaRuntimes)
    workingDir(rootProject.projectDir)
    commandLine(if (Os.isFamily(Os.FAMILY_WINDOWS)) "python" else "python3", "tools/verify_runtime_set.py", "--require-all",
        nativeResourceDir.dir("runtime").asFile.absolutePath, mediaRuntimeResources.get().dir("composemediaplayer/runtime").asFile.absolutePath)
}
tasks.configureEach {
    if (name.startsWith("publishJvmPublication")) dependsOn(verifyPublicationMediaRuntimes)
}

val verifyLegacyNativeSources by tasks.registering(Exec::class) {
    dependsOn(buildNativeMacOs, buildNativeWindows, buildNativeLinux)
    workingDir(rootProject.projectDir)
    commandLine(if (Os.isFamily(Os.FAMILY_WINDOWS)) "python" else "python3", "tools/legacy_native_manifest.py", "--verify", nativeResourceDir.asFile.absolutePath)
}
tasks.named("jvmProcessResources") { dependsOn(verifyLegacyNativeSources) }
