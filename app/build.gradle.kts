plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Numéro de compilation fourni par GitHub Actions (1 en local).
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "ca.anicet.ebikenav"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.anicet.ebikenav"
        minSdk = 26
        targetSdk = 34
        // Série « Pro » : 200 + n° de compilation, toujours supérieur à la 1.0.x
        // → s'installe PAR-DESSUS la version 1.0.4 déjà sur le téléphone.
        versionCode = 200 + buildNumber
        versionName = "2.0.$buildNumber"

        // Seulement les processeurs des téléphones (ARM) : APK ~2 fois plus léger
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Une seule clé de signature, gardée dans le projet : chaque nouvel APK
    // peut ainsi s'installer PAR-DESSUS l'ancien (sans désinstaller).
    signingConfigs {
        create("shared") {
            storeFile = file("ebike-nav.keystore")
            storePassword = "ebikenav"
            keyAlias = "ebikenav"
            keyPassword = "ebikenav"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Afficher chaque test (réussi / échoué) dans le journal GitHub
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Carte (MapLibre, moteur OpenGL)
    implementation("org.maplibre.gl:android-sdk:12.3.1")

    // GPS précis (services Google, présents sur le Samsung S10+)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Tests automatiques du moteur de guidage
    testImplementation("junit:junit:4.13.2")
}
