// 顶层构建文件：仅声明插件，具体配置在 :app 模块
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
