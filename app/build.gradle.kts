import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProperties = Properties()
var releaseSigningReady = false
var releaseStoreFile: File? = null
var releaseStorePassword: String? = null
var releaseKeyAlias: String? = null
var releaseKeyPassword: String? = null

val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    try {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
        val storeFileProperty = keystoreProperties.getProperty("storeFile")
        val storePasswordProperty = keystoreProperties.getProperty("storePassword")
        val keyAliasProperty = keystoreProperties.getProperty("keyAlias")
        val keyPasswordProperty = keystoreProperties.getProperty("keyPassword")
        if (storeFileProperty.isNullOrBlank() || storePasswordProperty.isNullOrBlank() || keyAliasProperty.isNullOrBlank() || keyPasswordProperty.isNullOrBlank()) {
            logger.warn("Firma release disattivata: valori mancanti in keystore.properties")
        } else {
            val resolvedStoreFile = rootProject.file(storeFileProperty)
            if (!resolvedStoreFile.isFile) {
                logger.warn("Firma release disattivata: keystore non trovato: " + resolvedStoreFile.absolutePath)
            } else {
                releaseStoreFile = resolvedStoreFile
                releaseStorePassword = storePasswordProperty
                releaseKeyAlias = keyAliasProperty
                releaseKeyPassword = keyPasswordProperty
                releaseSigningReady = true
            }
        }
    } catch (e: Exception) {
        logger.warn("Firma release disattivata: errore nella lettura di keystore.properties: " + e.message)
    }
} else {
    logger.warn("Firma release disattivata: keystore.properties non trovato nella cartella del progetto")
}

val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")

android {
    namespace = "com.pcaudio.minecraft_server"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pcaudio.minecraft_server"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    if (releaseSigningReady) {
        signingConfigs {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
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
            keepDebugSymbols += "**/libplayitd.so"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(generatedJniLibsDir)
        }
    }
}

tasks.register("downloadPlayitAgent") {
    val agentUrl = "https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-linux-aarch64"
    val targetFile = layout.buildDirectory.file("generated/jniLibs/arm64-v8a/libplayitd.so").get().asFile
    val minimumSize = 1000000L
    inputs.property("playitAgentUrl", agentUrl)
    outputs.file(targetFile)
    doLast {
        val isElf: (File) -> Boolean = { file ->
            if (!file.isFile || file.length() < 4L) {
                false
            } else {
                val header = ByteArray(4)
                val read = file.inputStream().use { input -> input.read(header) }
                read == 4 && header[0] == 0x7f.toByte() && header[1] == 0x45.toByte() && header[2] == 0x4c.toByte() && header[3] == 0x46.toByte()
            }
        }
        if (targetFile.isFile && targetFile.length() > minimumSize && isElf(targetFile)) {
            return@doLast
        }
        targetFile.parentFile.mkdirs()
        val tempFile = File(targetFile.parentFile, targetFile.name + ".part")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        try {
            val connection = URI(agentUrl).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            try {
                if (connection.responseCode != 200) {
                    throw GradleException("Download di libplayitd.so non riuscito da " + agentUrl + ": HTTP " + connection.responseCode)
                }
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (tempFile.length() < minimumSize || !isElf(tempFile)) {
                tempFile.delete()
                throw GradleException("Il file scaricato da " + agentUrl + " non è un binario ELF valido")
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.delete()
                throw GradleException("Impossibile spostare il binario in " + targetFile.absolutePath)
            }
        } catch (e: GradleException) {
            throw e
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw GradleException("Download di libplayitd.so non riuscito da " + agentUrl + ": " + e.message)
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadPlayitAgent")
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