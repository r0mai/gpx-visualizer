plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Dropbox app key is reused from the web app (site/dropbox.js). It is a
// PKCE "app key" with no secret, so it is safe to keep in source.
val dropboxAppKey = "kkon8scyxqw70w9"

android {
    namespace = "dev.r0mai.gpsvisualizer"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.r0mai.gpsvisualizer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Injected into AndroidManifest for the Dropbox OAuth redirect scheme
        // (db-<APP_KEY>) and exposed to code via BuildConfig.
        manifestPlaceholders["dropboxAppKey"] = dropboxAppKey
        buildConfigField("String", "DROPBOX_APP_KEY", "\"$dropboxAppKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        // Lets us use java.time (GPX timestamps) down to minSdk 24.
        isCoreLibraryDesugaringEnabled = true
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
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // MapLibre Native: rotation, 3D terrain (pitch), raster tourist tiles, GeoJSON overlays.
    implementation("org.maplibre.gl:android-sdk:11.5.2")

    // Dropbox: PKCE OAuth + offline (refresh token) + file listing/download.
    implementation("com.dropbox.core:dropbox-android-sdk:7.0.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
