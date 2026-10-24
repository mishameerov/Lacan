plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "org.lacan.parakeetprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.lacan.parakeetprobe"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }
}
dependencies {
    implementation("com.google.ai.edge.litert:litert:2.3.0")
    implementation("com.google.ai.edge.litert:litert-gpu:2.3.0")
}
