plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// versionName / versionCode can be overridden from the command line, which is
// how the release CI workflow stamps a build with the pushed git tag, e.g.:
//   gradle assembleRelease -PappVersionName=1.2.0 -PappVersionCode=42
val appVersionName: String = (project.findProperty("appVersionName") as String?) ?: "1.0.0"
val appVersionCode: Int = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.saad.w3schoolsapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.saad.w3schoolsapp"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // This is a personal, non-distributed app (see README) - it is
            // signed with the auto-generated debug keystore so that
            // `assembleRelease` produces something installable out of the
            // box in CI without needing a real signing key checked in.
            // Swap this for a real signingConfig if you ever need to.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
