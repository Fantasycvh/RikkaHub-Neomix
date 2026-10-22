plugins {
    id("rikkahub.android.library.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.androidrik.plugin.api"
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
