plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Publishing settings come from Gradle properties (with tools/dev.ps1: .toolchain/gradle-home/gradle.properties, or
// -P on the command line) or environment variables, never from this repository. See README.md, "Release builds".
fun publishingSetting(property: String, environment: String): String? =
    (providers.gradleProperty(property).orNull ?: providers.environmentVariable(environment).orNull)?.trim()?.takeIf { it.isNotEmpty() }
fun httpsUrl(name: String, value: String): String {
    if (!Regex("https://[^\\s\"\\\\]+").matches(value)) throw GradleException("$name must be an https:// URL")
    return value
}
val uploadStoreFile = publishingSetting("pub.upload.storeFile", "PUB_UPLOAD_STORE_FILE")
val uploadStorePassword = publishingSetting("pub.upload.storePassword", "PUB_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = publishingSetting("pub.upload.keyAlias", "PUB_UPLOAD_KEY_ALIAS") ?: "upload"
// keytool's default PKCS12 keystores use the store password for the key as well.
val uploadKeyPassword = publishingSetting("pub.upload.keyPassword", "PUB_UPLOAD_KEY_PASSWORD") ?: uploadStorePassword
val uploadSigning = uploadStoreFile != null && uploadStorePassword != null
// Where Report content sends reports (tools/report-endpoint). Without one, the report form says reporting is unavailable.
val reportEndpoint = publishingSetting("pub.reportEndpoint", "PUB_REPORT_ENDPOINT")?.let { httpsUrl("pub.reportEndpoint", it) }.orEmpty()
val privacyPolicyUrl = httpsUrl("pub.privacyPolicyUrl", publishingSetting("pub.privacyPolicyUrl", "PUB_PRIVACY_POLICY_URL")
    ?: "https://github.com/zeniazenin/PUB/blob/main/docs/privacy-policy.md")

android {
    namespace = "app.pub"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.pub"
        minSdk = 31
        targetSdk = 36
        versionCode = 11
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "REPORT_ENDPOINT", "\"$reportEndpoint\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyPolicyUrl\"")
    }
    signingConfigs {
        // The upload key signs bundles for Google Play; Play App Signing re-signs them for installation.
        if (uploadSigning) create("upload") {
            storeFile = file(uploadStoreFile!!)
            storePassword = uploadStorePassword
            keyAlias = uploadKeyAlias
            keyPassword = uploadKeyPassword
        }
    }
    buildTypes {
        // Debug builds are not representative of performance: Compose runs unoptimized and debuggable.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (uploadSigning) signingConfig = signingConfigs.getByName("upload")
        }
        // An installable optimized build for checking real performance on a phone. It is signed with the
        // local debug key, so it must never be published; ship `release` with a proper signing config.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = true }
}

// A Play upload must be signed with the upload key and able to send content reports (Google Play's AI-Generated
// Content policy). Stop before building rather than produce a bundle Play would reject or one without reporting.
gradle.taskGraph.whenReady {
    if (allTasks.none { it.project == project && (it.name == "bundleRelease" || it.name == "assembleRelease") }) return@whenReady
    val missing = buildList {
        if (!uploadSigning) add("an upload key (pub.upload.storeFile and pub.upload.storePassword)")
        else if (!file(uploadStoreFile!!).isFile) add("an upload keystore at $uploadStoreFile (pub.upload.storeFile)")
        if (reportEndpoint.isEmpty()) add("a report endpoint (pub.reportEndpoint)")
    }
    if (missing.isNotEmpty()) throw GradleException("Release builds need ${missing.joinToString(" and ")}. See README.md, \"Release builds\".")
}
dependencies {
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
