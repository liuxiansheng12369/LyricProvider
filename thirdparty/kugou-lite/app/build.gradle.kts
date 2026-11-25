import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    kotlin("plugin.serialization") version "2.1.21"
}

configure<ApplicationExtension> {
    namespace = "com.onedongua.kugouliteprovider"
    compileSdk {
        version = release(rootProject.extra.get("compileSdkVersion") as Int)
    }

    defaultConfig {
        applicationId = "com.onedongua.kugouliteprovider"
        minSdk = 27
        targetSdk = rootProject.extra.get("targetSdkVersion") as Int
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        getByName("debug") {
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":share:krckit"))
    implementation(libs.lyricon.lyric.model)

    implementation(libs.lyricon.provider)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.yukihookapi.api)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    compileOnly(libs.xposed.api)
    ksp(libs.yukihookapi.ksp.xposed)

    implementation(libs.androidx.core.ktx)
}