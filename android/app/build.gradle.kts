import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val privateSigningPropertiesFile = rootProject.file("../.release-private/signing/signing.properties")
val privateSigningProperties = Properties().apply {
    if (privateSigningPropertiesFile.isFile) {
        privateSigningPropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "org.isdm.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.isdm.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "0.5.8-nightly"

        buildConfigField(
            "String",
            "BETA_API_BASE",
            "\"https://tbnfnhepqfqjsowvzncl.supabase.co/functions/v1/beta-api\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (privateSigningPropertiesFile.isFile) {
            create("privateRelease") {
                storeFile = privateSigningPropertiesFile.parentFile.resolve(
                    privateSigningProperties.getProperty("storeFile"),
                )
                storePassword = privateSigningProperties.getProperty("storePassword")
                keyAlias = privateSigningProperties.getProperty("keyAlias")
                keyPassword = privateSigningProperties.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("privateRelease")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Known failure on Windows hosts only (see AGENTS.md, Known failures): java.io.File.renameTo
// cannot replace an existing file on Windows, so RollingDiagnosticStore.prune fails its check
// there. Android (Linux) replaces atomically, so the test runs everywhere else.
val windowsHostKnownFailures = listOf(
    "org.isdm.companion.platform.RollingDiagnosticStoreTest.keeps exact seven day boundary and recycles earlier lines within same day",
)
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").startsWith("Windows")) {
        windowsHostKnownFailures.forEach { filter.excludeTestsMatching(it) }
        doFirst { windowsHostKnownFailures.forEach { logger.warn("KNOWN FAILURE SKIPPED on Windows: $it") } }
    }
}

// Phone guard: any task that deploys to a device fails unless ANDROID_SERIAL names an emulator.
// Gradle otherwise deploys to every attached device, including the owner's phone.
tasks.configureEach {
    if (name.startsWith("install") || name.startsWith("uninstall") || name.startsWith("connected")) {
        doFirst {
            val serial = System.getenv("ANDROID_SERIAL").orEmpty()
            if (!Regex("""^emulator-\d+$""").matches(serial)) {
                throw GradleException(
                    "Refusing $path: ANDROID_SERIAL is '${serial.ifEmpty { "(unset)" }}', not an emulator. " +
                        "Fix: set ANDROID_SERIAL=emulator-5554 (open Galaxy_S24_FE_API_36 in T3's Device panel); never the owner's phone.",
                )
            }
        }
    }
}
