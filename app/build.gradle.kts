plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lelemusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lelemusic"
        minSdk = 26
        targetSdk = 34
        // 2026-09-12 用户约定：versionName 采用 YY.MM.DD（便于肉眼区分哪天的包）；
        // versionCode 同步用 YYMMDD 整数，单调递增不回退
        versionCode = 260920
        versionName = "26.09.20"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 构建指纹：versionCode/Name 是常量，区分不出「哪一次构建」，
        // 而我们多次因为「贴回来的自检日志其实是旧包跑的」而白排查一轮。
        // 把构建时刻烧进 BuildConfig，自检日志头部就能打印出来，一眼确认装的是哪个包。
        buildConfigField("long", "BUILD_TIME_MILLIS", "${System.currentTimeMillis()}L")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 架构文档 T01 要点 2 要求：HttpStack 中按 BuildConfig.DEBUG 决定是否加日志拦截器。
        // AGP 8.x 默认不生成 BuildConfig，因此这里必须显式打开，否则 BuildConfig 类不存在。
        buildConfig = true
    }

    composeOptions {
        // Compose Compiler 版本由 Kotlin 版本决定：Kotlin 1.9.22 <-> Compiler 1.5.8
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 2026-09-12 用户约定：APK 文件名 = LeleMusic-v<versionName>.apk（versionName = YY.MM.DD）。
    // 当天第二次起编译在复制到项目根目录时加 a1/a2 后缀（约定记录于 .workbuddy/memory/MEMORY.md）。
    applicationVariants.all {
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                ?.outputFileName = "LeleMusic-v${versionName}.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.squareup.okhttp)
    implementation(libs.squareup.okhttp.logging)
    implementation(libs.squareup.retrofit)
    implementation(libs.squareup.retrofit.converter.gson)
    implementation(libs.google.gson)

    implementation(libs.coil.compose)

    implementation(libs.kotlinx.coroutines.android)
}
