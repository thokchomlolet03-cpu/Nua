plugins { id("com.android.application") }
android {
    namespace = "org.nua.assess"
    compileSdk = 36
    defaultConfig { applicationId = "org.nua.assess"; minSdk = 26; targetSdk = 36; versionCode = 3; versionName = "0.2.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].assets.srcDir("../../web")
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")
}
