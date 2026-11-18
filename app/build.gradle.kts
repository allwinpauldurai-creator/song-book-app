import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** API origin for a flavour: -Psongbook.apiBaseUrl.<flavour>=... overrides gradle.properties. */
fun apiBaseUrl(flavour: String): String {
    val url = (findProperty("songbook.apiBaseUrl.$flavour") as String?)?.trim()?.trimEnd('/')
        ?: error("Missing Gradle property songbook.apiBaseUrl.$flavour")
    return "\"$url\""
}

// Release signing: CI passes the keystore through environment variables; locally an optional
// keystore.properties (git-ignored) can be used. Without either, release builds are unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)

android {
    namespace = "com.imsbooks.songbook"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.imsbooks.songbook"
        minSdk = 26
        targetSdk = 37
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0.0"
    }

    signingConfigs {
        val storeFile = signingValue("SONGBOOK_KEYSTORE_FILE", "storeFile")
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = signingValue("SONGBOOK_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("SONGBOOK_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("SONGBOOK_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        // Local Spring Boot backend, reached from the emulator (cleartext allowed in debug builds only).
        create("local") {
            dimension = "environment"
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            buildConfigField("String", "API_BASE_URL", apiBaseUrl("local"))
            resValue("string", "app_name", "IMS Books (local)")
        }
        // Azure dev environment.
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "API_BASE_URL", apiBaseUrl("dev"))
            resValue("string", "app_name", "IMS Books (dev)")
        }
        // Azure production.
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "API_BASE_URL", apiBaseUrl("prod"))
            resValue("string", "app_name", "IMS Books")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
