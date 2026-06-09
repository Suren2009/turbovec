import java.util.Locale

plugins {
    id("com.android.library")
}

val minAndroidApi = 23
val rustTarget = "aarch64-linux-android"
val rustAbi = "arm64-v8a"
val rustLibraryName = "libturbovec_jni.so"
val nativeDir = rootProject.layout.projectDirectory.dir("native")
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")

fun androidNdkHostTag(): String {
    val os = System.getProperty("os.name").lowercase(Locale.US)
    val arch = System.getProperty("os.arch").lowercase(Locale.US)
    return when {
        os.contains("linux") -> "linux-x86_64"
        os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> "darwin-arm64"
        os.contains("mac") -> "darwin-x86_64"
        os.contains("windows") -> "windows-x86_64"
        else -> throw GradleException("Unsupported Android NDK host OS: $os/$arch")
    }
}

fun ndkToolName(name: String): String =
    if (androidNdkHostTag().startsWith("windows")) "$name.cmd" else name

android {
    namespace = "com.turbovec.android"
    compileSdk = 36

    defaultConfig {
        minSdk = minAndroidApi

        ndk {
            abiFilters += rustAbi
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets["main"].jniLibs.srcDir(generatedJniLibsDir)
}

val cargoBuildArm64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the Rust JNI library for Android arm64-v8a."

    workingDir(nativeDir.asFile)
    commandLine("cargo", "build", "--release", "--target", rustTarget)

    inputs.dir(nativeDir)
    inputs.dir(rootProject.layout.projectDirectory.dir("../turbovec"))
    outputs.file(nativeDir.file("target/$rustTarget/release/$rustLibraryName"))

    doFirst {
        val ndkDir = android.ndkDirectory
        val toolchainBin = ndkDir.resolve("toolchains/llvm/prebuilt/${androidNdkHostTag()}/bin")
        val linker = toolchainBin.resolve(ndkToolName("aarch64-linux-android${minAndroidApi}-clang"))
        val archiver = toolchainBin.resolve(ndkToolName("llvm-ar"))

        if (!linker.isFile) {
            throw GradleException(
                "Android NDK linker not found at $linker. Install the Android NDK " +
                    "and run `rustup target add $rustTarget` before building the AAR.",
            )
        }

        environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
        environment("ANDROID_NDK_ROOT", ndkDir.absolutePath)
        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linker.absolutePath)
        environment("AR_aarch64_linux_android", archiver.absolutePath)
    }
}

val syncRustJniLibs by tasks.registering(Copy::class) {
    group = "build"
    description = "Copy the Rust JNI library into the Android jniLibs tree."

    dependsOn(cargoBuildArm64)
    from(nativeDir.file("target/$rustTarget/release/$rustLibraryName"))
    into(generatedJniLibsDir.map { it.dir(rustAbi) })
}

tasks.named("preBuild") {
    dependsOn(syncRustJniLibs)
}
