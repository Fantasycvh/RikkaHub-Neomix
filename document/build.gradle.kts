plugins {
    id("rikkahub.android.library")
}

android {
    namespace = "app.androidrik.document"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    // 解析器用 org.xmlpull.v1.XmlPullParser：Android 运行期自带，JVM 单测需要一份实现
    testImplementation(libs.kxml2)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
