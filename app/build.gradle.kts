plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.ebike.navigator"
 compileSdk = 35
 defaultConfig { applicationId = "com.ebike.navigator"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "0.3.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("org.maplibre.gl:android-sdk:11.8.0")
 testImplementation("junit:junit:4.13.2")
}