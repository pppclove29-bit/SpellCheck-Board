plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Firebase is configured by app/google-services.json, which is not in the repo (it carries project-specific ids).
 * The plugin hard-fails the build when that file is missing, so it is applied only when the file is there: the app
 * builds without Firebase and [com.typeright.app.analytics.AnalyticsFactory] falls back to a no-op sink.
 */
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

fun stringProp(name: String, default: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.typeright.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.typeright.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // OAuth "Web application" client id used as serverClientId for Google Sign-In (may be empty in debug).
        buildConfigField(
            "String", "GOOGLE_WEB_CLIENT_ID",
            stringProp("typeright.googleWebClientId", "").asBuildConfigString(),
        )
        // AdMob. The defaults are Google's official *test* ids: the app runs and shows test ads without an AdMob
        // account, but test ads send no SSV callback, so nothing is credited until the real ids are set
        // (typeright.admobAppId / typeright.admobRewardedUnitId in gradle.properties or -P flags).
        manifestPlaceholders["admobAppId"] =
            stringProp("typeright.admobAppId", "ca-app-pub-3940256099942544~3347511713")
        buildConfigField(
            "String", "ADMOB_REWARDED_UNIT_ID",
            stringProp("typeright.admobRewardedUnitId", "ca-app-pub-3940256099942544/5224354917").asBuildConfigString(),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":keyboard"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Google Sign-In via Credential Manager (host app only; the IME never signs in).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Google Play Billing (subscriptions). The server verifies every purchase token before granting PRO.
    implementation(libs.play.billing)

    // AdMob rewarded ads (host app only — never inside the IME). Rewards are credited by the SSV callback.
    implementation(libs.play.services.ads)

    // Firebase Analytics — retention / default-keyboard metrics. Inert until google-services.json is added.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
