plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.jarvis.companion"
    compileSdk = 35

    // Keep the earlier package tree in the repository for reference while
    // compiling only the components registered by the current manifest.
    sourceSets {
        getByName("main") {
            java.exclude("com/strognoff/jarvis/**")
        }
    }

    defaultConfig {
        applicationId = "dev.jarvis.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
    testImplementation("junit:junit:4.13.2")
}
