/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * 实现来源：qingning100607/LyricProvider-mi-music（Apache-2.0），
 * 本仓库按其思路重写为小米音乐（com.miui.player）歌词源，包名沿用本模块。
 */

plugins {
    id("lyricon.android.application")
    id("lyricon.yukihook")
}

android {
    namespace = "io.github.proify.lyricon.miuiprovider"

    defaultConfig {
        applicationId = "io.github.proify.lyricon.miuiprovider"
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        // 测试版也用正式证书，才能 adb install -r 覆盖已装的老版本
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":common:base:extensions-android"))
    implementation(project(":common:base:extensions-kt"))
    implementation(project(":common:codec:qrckit"))
}
