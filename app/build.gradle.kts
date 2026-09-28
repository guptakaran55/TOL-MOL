plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.signalscope.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.signalscope.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        viewBinding = true
    }
}

// Some IDE/test runners ask for the JVM-style `:app:testClasses` task. The
// Android Gradle plugin does not create that task for application modules; its
// equivalent work is variant-specific (`compileDebugUnitTestKotlin`,
// `compileDebugUnitTestJavaWithJavac`, `testDebugUnitTest`, etc.). This small
// alias keeps those runners happy without changing the Android build lifecycle.
tasks.register("testClasses") {
    group = "verification"
    description = "Compatibility alias for JVM test runners in this Android app module."
    dependsOn(tasks.matching {
        it.name == "compileDebugUnitTestKotlin" ||
            it.name == "compileDebugUnitTestJavaWithJavac"
    })
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // SharedPreferences encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── JVM unit tests ────────────────────────────────────────────────────────
    // The strategy engine is deliberately pure Kotlin — no Android, no network,
    // no broker — so its behaviour can be pinned by a plain JVM test with no
    // device and no emulator. That is what makes a parity gate on it practical.
    testImplementation("junit:junit:4.13.2")
    // TEST ONLY — never reaches the APK. android.jar stubs org.json in JVM unit tests
    // (every call throws "not mocked"), so a real implementation is needed to exercise
    // StrategyLabConfig.fromJson, which is the code path every uploaded config goes through.
    testImplementation("org.json:json:20240303")
}
