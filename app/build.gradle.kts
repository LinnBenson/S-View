plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.todu.sview"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.todu.sview"
        minSdk = 26
        targetSdk = 36
        versionCode = 161
        versionName = "1.5.13"

    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            proguardFiles("proguard-rules.pro")
            optimization {
                enable = true
                packageScope = setOf("io.todu.sview.**", "androidx.**", "kotlin.**", "kotlinx.**", "okhttp3.**", "okio.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.health:health-services-client:1.1.0")
    implementation("androidx.wear.tiles:tiles:1.5.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("androidx.wear.protolayout:protolayout:1.3.0")
    implementation("androidx.wear.compose:compose-material:1.7.2")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.noties:prism4j:2.0.0") { exclude(group = "org.jetbrains", module = "annotations-java5") }
    annotationProcessor("io.noties:prism4j-bundler:2.0.0")
    implementation("org.mozilla.geckoview:geckoview:160.0.20261116130455")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
