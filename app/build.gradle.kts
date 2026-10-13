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
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
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
