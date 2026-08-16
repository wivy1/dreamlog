plugins {
    id("com.android.application")
    id("androidx.room")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseStoreFile = providers.environmentVariable("DREAMLOG_RELEASE_STORE_FILE")
val releaseStorePassword = providers.environmentVariable("DREAMLOG_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("DREAMLOG_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("DREAMLOG_RELEASE_KEY_PASSWORD")
val releaseSigningInputs = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningInputCount = releaseSigningInputs.count { it.isPresent }

require(releaseSigningInputCount == 0 || releaseSigningInputCount == releaseSigningInputs.size) {
    "Set all DREAMLOG_RELEASE_* environment variables or leave all of them unset."
}

android {
    namespace = "com.wivy.dreamlog"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wivy.dreamlog"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (releaseSigningInputCount == releaseSigningInputs.size) {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningInputCount == releaseSigningInputs.size) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("deviceTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".devicetest"
            matchingFallbacks += listOf("debug")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("transcriptionFixture") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".transcriptionfixture"
            matchingFallbacks += listOf("debug")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("enrichmentFixture") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".enrichmentfixture"
            matchingFallbacks += listOf("debug")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testBuildType = "deviceTest"

    sourceSets {
        getByName("deviceTest").kotlin.srcDir("src/wakeCalibration/java")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val roomVersion = "2.8.4"

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    implementation(files("libs/sherpa-onnx-1.13.4-arm64.aar"))
    implementation(files("libs/onnxruntime-android-1.27.0-arm64-java-bridge.aar"))

    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
