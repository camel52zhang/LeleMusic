// 根构建脚本：只声明插件，不应用（apply false），具体配置在 app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
