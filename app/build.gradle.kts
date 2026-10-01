import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val uploadSigningFile = rootProject.file("signing/upload-signing.properties")
val uploadSigning = Properties().apply {
    if (uploadSigningFile.exists()) uploadSigningFile.inputStream().use { load(it) }
}
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")
// The demo has no Firebase client and must not require a second app registration.
tasks.configureEach {
    if (name.startsWith("processDemo") && name.endsWith("GoogleServices")) enabled = false
}
android {
    namespace = "com.thotapalli.visidock"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.thotapalli.visidock"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("visidock.versionCode").orElse("9").get().toInt()
        versionName = providers.gradleProperty("visidock.versionName").orElse("0.6.0").get()
        val imageApi = providers.gradleProperty("visidock.imageApiUrl").orElse("").get()
        buildConfigField("String", "IMAGE_API_URL", "\"" + imageApi.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (uploadSigningFile.exists()) create("upload") {
            storeFile = rootProject.file(uploadSigning.getProperty("storeFile"))
            storePassword = uploadSigning.getProperty("storePassword")
            keyAlias = uploadSigning.getProperty("keyAlias")
            keyPassword = uploadSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            // Keep the first internal release behavior identical to the tested app.
            // AAB delivery still splits native libraries by device architecture.
            isMinifyEnabled = false
            if (uploadSigningFile.exists()) signingConfig = signingConfigs.getByName("upload")
        }
    }
    flavorDimensions += "service"
    productFlavors {
        create("demo") {
            dimension = "service"
            applicationIdSuffix = ".demo"
            buildConfigField("boolean", "DEMO", "true")
        }
        create("cloud") {
            dimension = "service"
            buildConfigField("boolean", "DEMO", "false")
        }
    }
    sourceSets.getByName("main").assets.exclude("extraction/**")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
tasks.configureEach {
    if (name == "bundleCloudRelease" || name == "assembleCloudRelease") doFirst {
        check(uploadSigningFile.exists()) { "Initialize the upload key with scripts/Initialize-Signing.ps1 first." }
        check(file("google-services.json").exists()) { "Cloud releases require the Firebase client configuration." }
        check(providers.gradleProperty("visidock.imageApiUrl").orNull?.startsWith("https://") == true) { "Cloud releases require an HTTPS image API endpoint." }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.guava:guava:33.4.8-android")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    testImplementation("org.json:json:20240303")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
