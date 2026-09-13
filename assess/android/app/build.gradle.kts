plugins { id("com.android.application") }
android {
    namespace = "org.nua.assess"
    compileSdk = 36
    defaultConfig { applicationId = "org.nua.assess"; minSdk = 26; targetSdk = 36; versionCode = 6; versionName = "0.5.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].assets.srcDir("../../web")
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")
}
val verifyBundledAssets by tasks.registering {
    doLast {
        check(file("../../web/vendor/pdf.min.js").exists() && file("../../web/vendor/pdf.worker.min.js").exists()) {
            "PDF assets are missing. Run npm ci in assess before building Android."
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyBundledAssets) }
