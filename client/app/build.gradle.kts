import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
}

// Signing is optional. Credentials live in app/keystore.properties; if the file
// is missing the release APK is left unsigned. storeFile is resolved relative to
// this module, an absolute path works too. Format:
//
//   storeFile=my-release.p12
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
//   storeType=PKCS12
//
// To create a keystore:
//   keytool -genkeypair -v -keystore my-release.p12 -storetype PKCS12 \
//     -alias mykey -keyalg RSA -keysize 2048 -validity 10000
val keystorePropsFile = project.file("keystore.properties")
val hasReleaseKey = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasReleaseKey) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.hzgbai.smsforwarder"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hzgbai.smsforwarder"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                val storePath = keystoreProps.getProperty("storeFile")
                storeFile = project.file(storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                storeType = keystoreProps.getProperty("storeType") ?: "PKCS12"

                // v1 (needed below minSdk 24) and v2 are on by default. v3 is not,
                // so enable it explicitly: it supports signing key rotation, which
                // means the certificate can be swapped without a reinstall.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Release builds run lintVital by default, which needs an extra
        // com.android.tools.lint:lint-gradle download; skipping it does not
        // change the output.
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
}
