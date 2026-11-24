import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tw.com.recora"
    compileSdk = 36

    fun configValue(name: String): String? =
        providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
            ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

    val resolvedKeystoreFile = configValue("KEYSTORE_PATH")
        ?.let { path ->
            val f = File(path)
            if (f.isAbsolute) f else rootProject.file(path)
        }
        ?: file("mufu-debug.keystore")

    defaultConfig {
        applicationId = "tw.com.recora"
        minSdk = 30
        targetSdk = 36
        versionCode = configValue("versionCode")?.toInt() ?: 1
        versionName = configValue("versionName") ?: "1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = resolvedKeystoreFile
            storePassword = configValue("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = configValue("KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = configValue("KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // 本專案專用簽章：debug 與 release 統一使用 app/mufu-debug.keystore，可互通覆蓋升級
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // RangeDownloader 等用到 android.util.Log：JVM 單元測試讓它直接回預設值
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
