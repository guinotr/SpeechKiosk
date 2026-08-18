import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val adminPin = providers.gradleProperty("ADMIN_PIN")
    .orElse(localProperties.getProperty("ADMIN_PIN", "2468"))
    .get()

android {
    namespace = "fr.mamieturbo"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "fr.mamieturbo"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "ADMIN_PIN", "\"${adminPin.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "IMMERSIVE_MODE", "true")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    flavorDimensions += "transcriptionEngine"
    productFlavors {
        create("cloud") {
            dimension = "transcriptionEngine"
            buildConfigField("boolean", "LOCAL_ENGINE_INCLUDED", "false")
        }
        create("hybrid") {
            dimension = "transcriptionEngine"
            buildConfigField("boolean", "LOCAL_ENGINE_INCLUDED", "true")
            versionNameSuffix = "-hybrid"
        }
    }
    androidResources { noCompress += "onnx" }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    "hybridImplementation"(files("libs/sherpa-onnx-1.13.4.aar"))
    testImplementation("junit:junit:4.13.2")
}
