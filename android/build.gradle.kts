// AGP 9 ships built-in Kotlin support (KGP 2.2.10 as a runtime dependency).
// Declaring the Kotlin plugin here with `apply false` only pins the KGP version on the
// build classpath (and keeps the Compose compiler plugin version in sync with it).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Applied by :app only when app/google-services.json is present (see app/build.gradle.kts).
    alias(libs.plugins.google.services) apply false
}
