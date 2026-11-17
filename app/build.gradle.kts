plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.giorgi.silk"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.giorgi.silk"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "0.3-ios"
    }

    // One fixed key, kept in the repo, so every CI build can be installed as an
    // update over the previous one. Fine for a personal sideloaded app;
    // never reuse this key for anything published.
    signingConfigs {
        create("silk") {
            storeFile = file("silk.keystore")
            storePassword = "silkpass"
            keyAlias = "silk"
            keyPassword = "silkpass"
        }
    }

    buildTypes {
        // Release = not debuggable + R8-optimized. Debuggable apps run much
        // slower on ART, so release is what goes on the phone.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("silk")
        }
        debug {
            signingConfig = signingConfigs.getByName("silk")
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
}
