plugins { id("rikkahub.android.library.compose") }

android { namespace = "me.rerere.bettertext.text" }

dependencies {
    // Match the BOM published by BetterText v0.1.5, also used by its remaining Maven modules.
    api(platform("androidx.compose:compose-bom:2026.09.00"))
    api(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation("androidx.compose.foundation:foundation")
}
