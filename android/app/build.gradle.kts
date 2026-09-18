plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// `google-services.json` is never committed (docs/18 / .gitignore), so a checkout without
// it — CI, and any fork PR — must still build. Applying the plugin unconditionally would
// fail those builds outright, so it is applied only when the file is there.
//
// The Firebase SDK is compiled in either way. What the plugin contributes is the generated
// `values/values.xml` that `FirebaseInitProvider` reads; without it no `FirebaseApp` is
// created, `AppContainer` sees none, and analytics falls back to the local sink. That is
// the same "no transport" behaviour this module shipped before Firebase existed, which is
// why a config-less build stays honest rather than half-wired.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "com.parkingkok.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.parkingkok.app"
        minSdk = 29
        // targetSdk 36 is the Google Play requirement from 2026-08-31. Do not lower.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Room writes the schema of every version here. The files are committed so a schema
    // change shows up as a reviewable diff, and so ParkingDatabaseMigrationTest can assert
    // the current version was actually exported.
    ksp { arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.path) }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)

    // docs/07 §2: Analytics and Auth only. No Storage — the contract forbids it — and no
    // Firestore/Functions/App Check/Remote Config until the feature that needs them lands.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    // `Task.await()`, so the anonymous sign-in is an ordinary suspend call that a caller
    // can cancel, instead of a listener the app has to remember to detach.
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.sqlite.bundled.jvm)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// CLAUDE.md quality gate: Kotlin warnings are zero. Enforcing it here rather than reading
// build output means a new warning fails the build instead of sitting in a log.
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
