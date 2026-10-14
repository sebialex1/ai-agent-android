import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.sebialex.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sebialex.agent"
        minSdk = 26
        targetSdk = 34
        // CI passes VERSION_CODE (the run number) so every build is accepted as an update over the last.
        versionCode = (findProperty("VERSION_CODE") as String? ?: System.getenv("VERSION_CODE"))?.toIntOrNull() ?: 1
        versionName = (findProperty("VERSION_NAME") as String? ?: System.getenv("VERSION_NAME")) ?: "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // A stable key makes builds from any machine install over each other. It comes from the
    // environment (CI decodes the KEYSTORE_BASE64 secret) or Gradle properties; the keystore is never
    // committed. Without it the release build falls back to the debug key, so forks and local builds work.
    fun signingValue(name: String): String? = (findProperty(name) as String? ?: System.getenv(name))?.takeIf { it.isNotBlank() }
    val keystorePath = signingValue("KEYSTORE_PATH")
    val releaseSigning = if (keystorePath != null && file(keystorePath).isFile) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = signingValue("KEYSTORE_PASSWORD")
            keyAlias = signingValue("KEY_ALIAS")
            keyPassword = signingValue("KEY_PASSWORD") ?: signingValue("KEYSTORE_PASSWORD")
        }
    } else null

    buildTypes {
        release {
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { pickFirsts += "**/liblitertlm_jni.so" }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Kotlin 2.2 turned the old android.kotlinOptions {} block into an error.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // LiteRT-LM (and the kotlin-stdlib/kotlin-reflect it pulls in) is built with a newer
        // Kotlin than this project. Its metadata is read fine with this flag, exactly as Google's
        // own AI Edge Gallery app does with the same library version.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.litertlm.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
