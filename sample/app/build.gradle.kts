plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.androiduirenderer.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.androiduirenderer.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    // Same version the renderer adds to the Robolectric probe, so both classpaths agree.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")

    // The renderer's temporary probe is a JUnit 4 test; like the default Android Studio template,
    // the project provides JUnit itself.
    testImplementation("junit:junit:4.13.2")
}
