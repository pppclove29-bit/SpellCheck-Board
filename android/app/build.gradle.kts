import java.util.Properties

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

/**
 * 릴리스 서명 정보. 저장소에 **절대 넣지 않는다** — `android/keystore.properties`(gitignore 됨)에서 읽거나,
 * CI 에서는 같은 이름의 환경변수로 준다. 파일이 없으면 릴리스 빌드는 **서명 없이** 만들어진다
 * (디버그 키로 서명하지 않는다 — 그런 AAB 를 Play 에 올리면 업로드 키가 영구히 디버그 키로 굳는다).
 *
 * 만드는 법은 docs/human-todo.md 의 릴리스 서명 항목 참고.
 */
val keystoreProps: Properties? = rootProject.file("keystore.properties").takeIf(File::exists)?.let { f ->
    val props = Properties()
    f.inputStream().use(props::load)
    props
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
        // 첫 공개 출시. 클로즈드 테스트 트랙에 올릴 때마다 versionCode 를 올린다(Play 는 중복을 거부한다).
        versionCode = 1
        versionName = "1.0.0"
        // OAuth "Web application" client id used as serverClientId for Google Sign-In (may be empty in debug).
        buildConfigField(
            "String", "GOOGLE_WEB_CLIENT_ID",
            stringProp("typeright.googleWebClientId", "").asBuildConfigString(),
        )
    }

    // :keyboard 와 같은 축. ondevice = 출시 형태(AI·로그인·결제·광고 없음), cloud = 전부 되살린 형태.
    // 소스셋(src/ondevice, src/cloud), 매니페스트, 의존성이 여기서 함께 갈린다.
    flavorDimensions += "features"
    productFlavors {
        create("ondevice") {
            dimension = "features"
            isDefault = true
        }
        create("cloud") {
            dimension = "features"
            // AdMob. 기본값은 Google 공식 *테스트* ID다. 테스트 광고는 SSV 콜백을 보내지 않으므로 실제 ID를 넣기
            // 전까지 충전은 되지 않는다. ondevice 에는 이 값도, AdMob SDK 도, 매니페스트 항목도 들어가지 않는다.
            manifestPlaceholders["admobAppId"] =
                stringProp("typeright.admobAppId", "ca-app-pub-3940256099942544~3347511713")
            buildConfigField(
                "String", "ADMOB_REWARDED_UNIT_ID",
                stringProp("typeright.admobRewardedUnitId", "ca-app-pub-3940256099942544/5224354917")
                    .asBuildConfigString(),
            )
        }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 을 끈 채로 첫 출시를 낸다. 키보드는 잘못 줄이면 **사용자가 글자를 못 치는** 상태가 되는데,
            // 릴리스 키스토어가 아직 없어 축소된 릴리스 빌드를 실기기에서 검증할 방법이 없다.
            // 첫 출시의 목적은 리텐션 측정이지 용량 절감이 아니다(무축소 AAB 도 14MB 대).
            // 키스토어가 생기고 릴리스 빌드를 실기기로 한 번 훑은 뒤 켠다 — human-todo 참고.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // keystore.properties 가 없으면 서명 없이 빌드된다 (위 주석 참고).
            signingConfig = signingConfigs.findByName("release")
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

    // 결제·광고는 cloud 플레이버에만 들어간다. 출시 AAB(ondevice)에는 두 SDK 도, AdMob 테스트 앱 ID 도 없다.
    // Google Play Billing (subscriptions). The server verifies every purchase token before granting PRO.
    "cloudImplementation"(libs.play.billing)

    // AdMob rewarded ads (host app only — never inside the IME). Rewards are credited by the SSV callback.
    "cloudImplementation"(libs.play.services.ads)

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
