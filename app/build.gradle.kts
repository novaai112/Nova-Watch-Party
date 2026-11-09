import java.util.Properties
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Push notifications only turn on when you add app/google-services.json (see API_KEYS_GUIDE.md)
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

// API keys live in local.properties (never committed) or environment variables
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Host used for verified Android App Links (https://<host>/r/CODE): INVITE_BASE_URL, else the server URL
fun hostOf(url: String): String = try { URI(url.replaceFirst("ws", "http")).host ?: "" } catch (e: Exception) { "" }
fun secret(name: String): String = (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim()

android {
    namespace = "com.nova.watchparty"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.nova.watchparty"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "2.3"
        manifestPlaceholders["cleartext"] = "true"
        manifestPlaceholders["inviteHost"] = hostOf(secret("INVITE_BASE_URL").ifBlank { secret("SERVER_URL") }).ifBlank { "localhost" }
        buildConfigField("String", "YOUTUBE_API_KEY", "\"${secret("YOUTUBE_API_KEY")}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${secret("TMDB_API_KEY")}\"")
        buildConfigField("String", "GIPHY_API_KEY", "\"${secret("GIPHY_API_KEY")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${secret("GOOGLE_WEB_CLIENT_ID")}\"")
        buildConfigField("String", "TURN_URL", "\"${secret("TURN_URL")}\"")
        buildConfigField("String", "TURN_USERNAME", "\"${secret("TURN_USERNAME")}\"")
        buildConfigField("String", "TURN_CREDENTIAL", "\"${secret("TURN_CREDENTIAL")}\"")
        buildConfigField("String", "INVITE_BASE_URL", "\"${secret("INVITE_BASE_URL")}\"")
        buildConfigField("String", "DEFAULT_SERVER_URL",
            "\"${secret("SERVER_URL").ifBlank { "ws://10.0.2.2:8080" }}\"")
    }
    signingConfigs {
        create("release") {
            val path = secret("KEYSTORE_PATH")
            if (path.isNotBlank()) {
                storeFile = file(path); storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS"); keyPassword = secret("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (secret("KEYSTORE_PATH").isNotBlank()) signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["cleartext"] = "false"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("io.getstream:stream-webrtc-android:1.1.1")            // voice chat (WebRTC)
    implementation("com.google.mlkit:translate:17.0.3")                   // on-device auto-translate (free, no key)
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging")             // push notifications
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
}
