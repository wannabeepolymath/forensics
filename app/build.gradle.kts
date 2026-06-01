plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

android {
    namespace = "com.forensics.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.forensics.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        create("release") {
            // Credentials come from the GLOBAL ~/.gradle/gradle.properties (never committed):
            //   FORENSICS_KEYSTORE, FORENSICS_KEYSTORE_PASSWORD, FORENSICS_KEY_ALIAS, FORENSICS_KEY_PASSWORD
            // Absent (fresh clone / CI) => release stays unsigned instead of failing configuration.
            val ksPath = project.findProperty("FORENSICS_KEYSTORE") as String?
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = project.findProperty("FORENSICS_KEYSTORE_PASSWORD") as String?
                keyAlias = project.findProperty("FORENSICS_KEY_ALIAS") as String?
                keyPassword = project.findProperty("FORENSICS_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            // Attach the release signing config only when a keystore is actually configured, so
            // `assembleRelease` on a machine without creds produces an (unsigned) APK rather than
            // erroring on a half-populated signing config.
            if (project.findProperty("FORENSICS_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false // no shrinker/proguard configured yet
        }
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
}
