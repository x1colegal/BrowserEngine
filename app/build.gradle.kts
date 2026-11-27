plugins {
    id("com.android.application") version "9.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

android {
    namespace = "com.x1colegal.browserengine"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.x1colegal.browserengine"
        minSdk = 21
        targetSdk = 36
        versionCode = 27
        versionName = "2.8"
    }
    flavorDimensions += "engine"
    productFlavors {
        create("legacy") {
            dimension = "engine"
            minSdk = 21
            versionNameSuffix = "-legacy"
            buildConfigField("String", "GECKOVIEW_MAJOR", "\"143\"")
        }
        create("modern") {
            dimension = "engine"
            minSdk = 26
            versionNameSuffix = "-modern"
            buildConfigField("String", "GECKOVIEW_MAJOR", "\"163\"")
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
    packaging.resources.excludes += setOf("META-INF/versions/**", "META-INF/INDEX.LIST")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.work:work-runtime:2.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    "legacyImplementation"("org.mozilla.geckoview:geckoview:143.0.20251003115653")
    "modernImplementation"("org.mozilla.geckoview:geckoview-nightly:+")
    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-dash:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")
}
