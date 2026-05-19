plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "it.dewarp"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.dewarp"
        minSdk = 26          // Android 8.0+: copre tutti i dispositivi Samsung recenti
        targetSdk = 35
        versionCode = 100
        versionName = "0.1.0"

        ndk {
            // OpenCV Android pubblica binari per queste ABI
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }

    signingConfigs {
        create("release") {
            // Keystore committata nel repo: serve a far si' che ogni build prodotta
            // dalla CI sia firmata con la STESSA chiave, e quindi gli APK siano
            // installabili come update sopra le versioni precedenti.
            // Trade-off: chiunque accede al repo puo' firmare APK come "dewarp".
            // OK per uso personale / sideload; per Play Store servirebbe una
            // keystore separata in GitHub Secret.
            storeFile = file("release.jks")
            storePassword = "dewarp-release"
            keyAlias = "dewarp"
            keyPassword = "dewarp-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
        // OpenCV pubblica .so in librerie native: lasciamo defaults
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.opencv)
    implementation(libs.pdfbox.android)
    implementation(libs.kotlinx.coroutines.android)
}
