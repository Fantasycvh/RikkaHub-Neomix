plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.androidrik.plugin.manager"
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":plugin-api"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
