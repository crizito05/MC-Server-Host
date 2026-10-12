import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
var releaseSigningReady = false

if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    val storeFilePath = keystoreProperties.getProperty("storeFile")
    val storePasswordValue = keystoreProperties.getProperty("storePassword")
    val keyAliasValue = keystoreProperties.getProperty("keyAlias")
    val keyPasswordValue = keystoreProperties.getProperty("keyPassword")

    if (storeFilePath.isNullOrBlank()) {
        logger.warn("Firma release disattivata: in keystore.properties manca la voce storeFile")
    } else {
        val resolvedStoreFile = rootProject.file(storeFilePath)
        if (!resolvedStoreFile.exists()) {
            logger.warn("Firma release disattivata: keystore non trovato in ${resolvedStoreFile.absolutePath}")
        } else if (storePasswordValue.isNullOrBlank() || keyAliasValue.isNullOrBlank() || keyPasswordValue.isNullOrBlank()) {
            logger.warn("Firma release disattivata: in keystore.properties manca storePassword, keyAlias o keyPassword")
        } else {
            releaseSigningReady = true
        }
    }
} else {
    logger.warn("Firma release disattivata: keystore.properties non esiste in ${keystorePropertiesFile.absolutePath}")
}

android {
    namespace = "com.pcaudio.minecraft_server"
    compileSdk = 34

    if (releaseSigningReady) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.pcaudio.minecraft_server"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("so", "1", "")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
    implementation("io.coil-kt:coil:2.6.0")
}