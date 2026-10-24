plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "org.lacan.fixture"; compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "org.lacan.fixture"; minSdk = 33; targetSdk = 36; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
