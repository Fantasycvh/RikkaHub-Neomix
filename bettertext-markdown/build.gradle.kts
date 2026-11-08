plugins { id("rikkahub.android.library.compose") }

android { namespace = "me.rerere.bettertext.markdown" }

dependencies {
    api(project(":bettertext-text"))
    implementation("androidx.compose.foundation:foundation")
    // Preserve the published v0.1.5 dependency: Gradle selected this over the app's d79a97cc8e.
    // Dropping it silently changes the resolved grammar (e.g. single-tilde strikethrough).
    implementation("com.github.rikkahub:markdown:25d181c")
    implementation("org.jsoup:jsoup:1.23.2")
    testImplementation(libs.junit)
}
