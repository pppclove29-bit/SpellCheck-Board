import org.gradle.api.tasks.PathSensitivity
import com.android.build.api.variant.LibraryVariant

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

/** Repo-level `shared/` directory: single source of truth for the on-device rules. */
val sharedDir: File = rootProject.layout.projectDirectory.dir("../shared").asFile.canonicalFile

fun stringProp(name: String, default: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.typeright.keyboard"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // Empty SUPABASE_URL => backend dev mode: requests carry X-Dev-User-Id instead of a bearer token.
        buildConfigField("String", "SUPABASE_URL", stringProp("typeright.supabaseUrl", "").asBuildConfigString())
        buildConfigField("String", "SUPABASE_ANON_KEY", stringProp("typeright.supabaseAnonKey", "").asBuildConfigString())
    }

    // 서버·계정·결제가 필요한 기능 전체를 켜고 끄는 축. `ondevice`(기본 출시 형태)에서는 AI·로그인·결제·광고가
    // 없고, `cloud`에서는 전부 살아난다. :app 이 같은 dimension 을 써서 소스셋·매니페스트·의존성까지 함께 갈린다.
    // 자세한 것은 FeatureFlags.kt 와 docs/planning-and-dev-log.md 14절.
    flavorDimensions += "features"
    productFlavors {
        create("ondevice") {
            dimension = "features"
            buildConfigField("boolean", "CLOUD_FEATURES", "false")
        }
        create("cloud") {
            dimension = "features"
            buildConfigField("boolean", "CLOUD_FEATURES", "true")
        }
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
            // 이 파일들은 모듈 밖에 있어서 기본적으로 태스크 입력이 아니다. 선언하지 않으면 규칙이나
            // 등록 정보를 고쳐도 Gradle 이 테스트를 UP-TO-DATE 로 건너뛰어 **낡은 결과로 통과한다.**
            test.inputs.dir(sharedDir).withPropertyName("sharedRules")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            test.inputs.file(File(sharedDir.parentFile, "docs/store-listing.md"))
                .withPropertyName("storeListing")
                .withPathSensitivity(PathSensitivity.RELATIVE)
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
