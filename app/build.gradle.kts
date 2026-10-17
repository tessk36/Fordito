import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "hu.orajegyzet"
    compileSdk = 36

    defaultConfig {
        applicationId = "hu.orajegyzet"
        minSdk = 31   // 1.1: a LiteRT-LM (Gemma 4) Android 12+ készüléket igényel
        targetSdk = 36
        versionCode = 15
        versionName = "1.1.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.clear(); abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            val p = project.properties
            val ksPath = (p["ORAJEGYZET_KEYSTORE"] as String?) ?: System.getenv("ORAJEGYZET_KEYSTORE") ?: "release.jks"
            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = (p["ORAJEGYZET_KS_PASS"] as String?) ?: System.getenv("ORAJEGYZET_KS_PASS") ?: "orajegyzet_pass"
                keyAlias = (p["ORAJEGYZET_KEY_ALIAS"] as String?) ?: System.getenv("ORAJEGYZET_KEY_ALIAS") ?: "orajegyzet_alias"
                keyPassword = (p["ORAJEGYZET_KEY_PASS"] as String?) ?: System.getenv("ORAJEGYZET_KEY_PASS") ?: "orajegyzet_pass"
            } else {
                storeFile = getByName("debug").storeFile
                storePassword = getByName("debug").storePassword
                keyAlias = getByName("debug").keyAlias
                keyPassword = getByName("debug").keyPassword
            }
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += "x86_64" }
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    kotlinOptions {
        jvmTarget = "17"
        // A LiteRT-LM könyvtár újabb Kotlinnal készülhetett; ez engedi a régebbi fordítónak beolvasni.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs {
            excludes += listOf("lib/mips/**", "lib/mips64/**", "lib/armeabi/**", "lib/x86/**")
            pickFirsts += listOf("**/liblitertlm_jni.so")
            useLegacyPackaging = false
        }
    }

    bundle {
        abi { enableSplit = true }
        language { enableSplit = false }
    }
    applicationVariants.all {
        outputs.configureEach {
            (this as? BaseVariantOutputImpl)?.outputFileName =
                "AssiSten-s-${versionName}-${buildType.name}.apk"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// A Whisper-könyvtár a legújabb androidx.core-t húzná be (1.16.0), ami újabb
// Gradle plugint kérne. Lekényszerítjük a jelenlegi pluginhoz illő 1.13.1-re.
configurations.all {
    resolutionStrategy {
        force(
            "androidx.core:core:1.13.1",
            "androidx.core:core-ktx:1.13.1"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Háttérmunka + beállítások
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Widget
    implementation("androidx.glance:glance-appwidget:1.1.0")

    // HTTP (Gemini API)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // A Vosk (és a JNA) 1.0.4-től kikerült: a helyi beszédfelismerő a Whisper.

    // Offline STT (Whisper, large-v3-turbo) — helyi forrás-modul, magyarra patchelve
    // (jni.c: params.language = "hu"). Natív fordítás NDK + CMake szükséges.
    implementation(project(":whispercore"))

    // Offline LLM (Gemma 4) a LiteRT-LM motoron — modellfájl (.litertlm) külön, lásd README.
    // Rögzített verzió: ugyanez fut a Google AI Edge Gallery 1.0.20-ban a Gemma 4 E2B-vel.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

    // Email (SMTP)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
