import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("rikkahub.android.library.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.androidrik.ai"

    defaultConfig {
//        externalNativeBuild {
//            cmake {
//                cppFlags += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
//                abiFilters += listOf("arm64-v8a", "x86_64")
//            }
//        }
    }

//    externalNativeBuild {
//        cmake {
//            path = file("src/main/cpp/CMakeLists.txt")
//            version = "3.22.1"
//        }
//    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
    }

    // JVM 单测中 android.util.Log 等桩方法返回默认值（与 app 模块同款）：
    // 代理语义矩阵等纯逻辑不宜因打日志而无法单测
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":common"))

    // Compose
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)

    // okhttp
    api(libs.okhttp)
    api(libs.okhttp.sse)
    api(libs.okhttp.logging)

    // kotlinx
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)

    // tests
    testImplementation(libs.junit)
    // 只在测试里起本地服务端：验证「真实请求链路上的请求头」而不必碰真机配置
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
