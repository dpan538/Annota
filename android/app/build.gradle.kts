plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.dpan538.annota"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.dpan538.annota"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.gradleProperty("annotaVersionCode").orElse("2").get().toInt()
        versionName = providers.gradleProperty("annotaVersionName").orElse("0.1.1-dev").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "FEEDBACK_ENDPOINT",
            "\"${providers.gradleProperty("feedbackEndpoint").orElse("").get()}\"",
        )
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (System.getenv("ANNOTA_KEYSTORE") != null)
            create("production") {
                storeFile = file(System.getenv("ANNOTA_KEYSTORE"))
                storePassword = System.getenv("ANNOTA_STORE_PASSWORD")
                keyAlias = System.getenv("ANNOTA_KEY_ALIAS")
                keyPassword = System.getenv("ANNOTA_KEY_PASSWORD")
            }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("production")
        }
    }
    packaging {
        resources.excludes +=
            setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    androidResources { noCompress += "sqlite" }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.86")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
    implementation("org.bouncycastle:bcutil-jdk15to18:1.86")
    implementation("com.android.tools.build:apksig:8.13.2")
    implementation("androidx.core:core-ktx:1.17.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
