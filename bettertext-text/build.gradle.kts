plugins { id("rikkahub.android.library.compose") }

android { namespace = "me.rerere.bettertext.text" }

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation("androidx.compose.foundation:foundation")
}
