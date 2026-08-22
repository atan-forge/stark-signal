import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val releaseAssetDir = providers.gradleProperty("starkReleaseAssetsDir").orElse("local-models/release-base")
val releaseProvenance = rootProject.file("${releaseAssetDir.get()}/models/model-provenance.json")
fun releaseProvenanceValue(field: String) = providers.provider {
    if (!releaseProvenance.isFile) return@provider ""
    Regex("\\\"$field\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(releaseProvenance.readText())?.groupValues?.get(1).orEmpty()
}
val releaseModelId = releaseProvenanceValue("candidateId")
val releaseModelSha256 = releaseProvenanceValue("sha256")
val releaseModelByteCount = providers.provider {
    if (!releaseProvenance.isFile) return@provider "0"
    Regex("\\\"byteCount\\\"\\s*:\\s*(\\d+)").find(releaseProvenance.readText())?.groupValues?.get(1) ?: "0"
}
val releaseModelSourceUrl = releaseProvenanceValue("sourceUrl")
val releaseModelLicense = releaseProvenanceValue("modelLicense")
val releaseModelEngineRevision = releaseProvenanceValue("engineRevision")

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

android {
    namespace = "com.atan.starkaudio"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.atan.starkaudio"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BENCHMARK_MODEL_ID", "\"\"")
        buildConfigField("String", "BUNDLED_MODEL_ID", "\"\"")
        buildConfigField("String", "BUNDLED_MODEL_FILE", "\"\"")
        buildConfigField("String", "BUNDLED_MODEL_SHA256", "\"\"")
        buildConfigField("long", "BUNDLED_MODEL_BYTE_COUNT", "0L")
        buildConfigField("String", "BUNDLED_MODEL_SOURCE_URL", "\"\"")
        buildConfigField("String", "BUNDLED_MODEL_LICENSE", "\"\"")
        buildConfigField("String", "BUNDLED_MODEL_ENGINE_REVISION", "\"\"")

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += setOf("arm64-v8a", "x86_64")
            }
        }
        release {
            ndk {
                abiFilters += "arm64-v8a"
            }
            optimization {
                enable = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            buildConfigField("String", "BUNDLED_MODEL_ID", "\"${releaseModelId.get()}\"")
            buildConfigField("String", "BUNDLED_MODEL_FILE", "\"selected-model.bin\"")
            buildConfigField("String", "BUNDLED_MODEL_SHA256", "\"${releaseModelSha256.get()}\"")
            buildConfigField("long", "BUNDLED_MODEL_BYTE_COUNT", "${releaseModelByteCount.get()}L")
            buildConfigField("String", "BUNDLED_MODEL_SOURCE_URL", "\"${releaseModelSourceUrl.get()}\"")
            buildConfigField("String", "BUNDLED_MODEL_LICENSE", "\"${releaseModelLicense.get()}\"")
            buildConfigField("String", "BUNDLED_MODEL_ENGINE_REVISION", "\"${releaseModelEngineRevision.get()}\"")
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            buildConfigField("String", "BENCHMARK_MODEL_ID", "\"${providers.gradleProperty("starkBenchmarkModelId").orElse("").get()}\"")
            buildConfigField("String", "BUNDLED_MODEL_ID", "\"${providers.gradleProperty("starkBenchmarkModelId").orElse("").get()}\"")
            buildConfigField("String", "BUNDLED_MODEL_FILE", "\"selected-model.bin\"")
            buildConfigField("String", "BUNDLED_MODEL_SHA256", "\"${providers.gradleProperty("starkBenchmarkModelSha256").orElse("").get()}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    sourceSets.getByName("release").assets.directories.add(
        rootProject.file(providers.gradleProperty("starkReleaseAssetsDir").orElse("local-models/release-base").get()).absolutePath
    )
    sourceSets.getByName("benchmark").assets.directories.add(
        rootProject.file(providers.gradleProperty("starkBenchmarkAssetsDir").orElse("local-models/benchmark-selected").get()).absolutePath
    )
}

val benchmarkAssetDir = providers.gradleProperty("starkBenchmarkAssetsDir").orElse("local-models/benchmark-selected")
val benchmarkModelId = providers.gradleProperty("starkBenchmarkModelId").orElse("")
val benchmarkModelSha256 = providers.gradleProperty("starkBenchmarkModelSha256").orElse("")

val verifyBenchmarkModel by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the locally staged benchmark model before creating a sideloadable benchmark APK."
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        rootProject.file("tools/Test-StarkBenchmarkAssets.ps1").absolutePath,
        "-AssetDirectory", rootProject.file(benchmarkAssetDir.get()).absolutePath,
        "-CandidateId", benchmarkModelId.get(),
        "-ExpectedSha256", benchmarkModelSha256.get()
    )
}

tasks.matching { it.name.startsWith("assembleBenchmark", ignoreCase = true) }.configureEach {
    dependsOn(verifyBenchmarkModel)
}

val verifyReleaseModel by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the locally staged Base model before creating a release APK."
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        rootProject.file("tools/Test-StarkReleaseModelAssets.ps1").absolutePath,
        "-AssetDirectory", rootProject.file(releaseAssetDir.get()).absolutePath
    )
}

tasks.matching { it.name.startsWith("assembleRelease", ignoreCase = true) }.configureEach {
    dependsOn(verifyReleaseModel)
}

val verifyMergedReleaseManifest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects sensitive permissions and unexpected exported release components."
    dependsOn("processReleaseMainManifest")
    val manifestDirectory = layout.buildDirectory.dir("intermediates/merged_manifests/release").get().asFile.absolutePath
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", rootProject.file("tools/Test-StarkMergedManifest.ps1").absolutePath, "-ManifestDirectory", manifestDirectory)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.biometric)
    // Required directly because app-lock authentication uses FragmentActivity.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}
