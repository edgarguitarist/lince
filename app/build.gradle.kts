plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.edgarguitarist.lince"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.edgarguitarist.lince"
        minSdk = 28
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.1"
        // `-PidSufijo=.mt` instala una copia aparte (para probar sin pisar la versión instalada).
        (project.findProperty("idSufijo") as String?)?.let { applicationIdSuffix = it }
    }

    // Un APK por arquitectura: Python + ffmpeg pesan, y así cada uno lleva solo lo suyo.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // La llave de release vive fuera del repo; las claves salen de ~/.gradle/gradle.properties
    // (LINCE_STORE_FILE, LINCE_STORE_PASSWORD, LINCE_KEY_ALIAS, LINCE_KEY_PASSWORD) o de variables de entorno.
    fun clave(nombre: String): String? =
        (project.findProperty(nombre) as String?) ?: System.getenv(nombre)
    val almacen = clave("LINCE_STORE_FILE")?.takeIf { file(it).exists() }
    signingConfigs {
        if (almacen != null) {
            create("release") {
                storeFile = file(almacen)
                storePassword = clave("LINCE_STORE_PASSWORD")
                keyAlias = clave("LINCE_KEY_ALIAS")
                keyPassword = clave("LINCE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 quita lo que no se usa (sobre todo los íconos): baja el APK unos 60 MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Con la llave de release si está configurada; si no, la debug (para compilar sin ella).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // youtubedl-android ejecuta binarios nativos desde la carpeta de librerías: tienen que ir extraídos.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:$ytdl")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
}
