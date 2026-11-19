plugins {
    id("com.android.application")
}

android {
    namespace = "com.tgmod.profileinfo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tgmod.profileinfo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("org.luckypray:dexkit:2.0.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
