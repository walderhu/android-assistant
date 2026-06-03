plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.assistant.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.assistant.app"
        // Подняли до 26 ради Health Connect (androidx.health.connect:connect-client
        // сам декларирует minSdk 26 начиная с 1.0.0-alpha07).
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            val envFile = rootProject.file("../.env")
            val envMap = if (envFile.exists()) {
                envFile.readLines().mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2 && !parts[0].startsWith("#")) parts[0].trim() to parts[1].trim() else null
                }.toMap()
            } else emptyMap()
            val orKey = (project.findProperty("OPENROUTER_API_KEY") as String?)
                ?: envMap["OPENROUTER_API_KEY"] ?: ""
            val groqKey = (project.findProperty("GROQ_API_KEY") as String?)
                ?: envMap["GROQ_API_KEY"] ?: ""
            buildConfigField("String", "OPENROUTER_API_KEY", "\"$orKey\"")
            buildConfigField("String", "GROQ_API_KEY", "\"$groqKey\"")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Health Connect 1.1.0 жёстко просит kotlin-stdlib 2.0.21 в <dependencyManagement>,
// но мы сидим на Kotlin 1.9.20 — заставляем Gradle использовать совместимые версии.
configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.20",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.20",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20",
            "org.jetbrains.kotlin:kotlin-stdlib-common:1.9.20"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    // 1.1.0 требует compileSdk 36 / AGP 8.9.1 / minSdk 26 — у нас AGP 8.2 / compileSdk 34 / minSdk 24.
    // 1.0.0-beta01 держит API, который мы используем (ActiveCaloriesBurnedRecord + aggregate()).
    implementation("androidx.health.connect:connect-client:1.0.0-alpha07")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
