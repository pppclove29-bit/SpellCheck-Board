import com.android.build.api.variant.LibraryVariant

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

/** Repo-level `shared/` directory: single source of truth for the on-device rules. */
val sharedDir: File = rootProject.layout.projectDirectory.dir("../shared").asFile.canonicalFile

fun stringProp(name: String, default: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

fun boolProp(name: String, default: Boolean): Boolean =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }?.toBooleanStrict() ?: default

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.typeright.keyboard"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // Single kill switch for everything that needs a server, an account or a purchase: AI 문맥 교정, 쿼터·충전,
        // 보상형 광고, PRO 구독, 구글 로그인, 단축어 동기화. false => 온디바이스 전용 빌드(계정·키 없이 스토어 제출 가능).
        // 되살릴 때는 gradle.properties 에 `typeright.cloudFeatures=true` 한 줄. 자세한 것은 FeatureFlags.kt.
        buildConfigField("boolean", "CLOUD_FEATURES", boolProp("typeright.cloudFeatures", false).toString())
        // Empty SUPABASE_URL => backend dev mode: requests carry X-Dev-User-Id instead of a bearer token.
        buildConfigField("String", "SUPABASE_URL", stringProp("typeright.supabaseUrl", "").asBuildConfigString())
        buildConfigField("String", "SUPABASE_ANON_KEY", stringProp("typeright.supabaseAnonKey", "").asBuildConfigString())
    }

    buildTypes {
        debug {
            buildConfigField(
                "String", "API_BASE_URL",
                stringProp("typeright.apiBaseUrl.debug", "http://10.0.2.2:8790").asBuildConfigString(),
            )
        }
        release {
            buildConfigField(
                "String", "API_BASE_URL",
                stringProp("typeright.apiBaseUrl.release", "https://api.typeright.example").asBuildConfigString(),
            )
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

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // Tests read shared/*.json via a path relative to the module dir; also exported explicitly.
            test.systemProperty("typeright.sharedDir", sharedDir.absolutePath)
        }
    }
}

/**
 * Copies shared/korean-rules.json (and nothing else from shared/) into a generated assets
 * directory so the JSON is never duplicated by hand in the source tree.
 */
abstract class CopySharedRulesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        rulesFile.get().asFile.copyTo(File(out, "korean-rules.json"), overwrite = true)
    }
}

val copySharedRules = tasks.register<CopySharedRulesTask>("copySharedRules") {
    rulesFile.set(File(sharedDir, "korean-rules.json"))
}

androidComponents {
    onVariants { variant: LibraryVariant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copySharedRules, CopySharedRulesTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
