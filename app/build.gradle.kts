plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // 1. Добавьте этот плагин для Firebase
    id("com.google.gms.google-services")
}

android {
    namespace = "com.messenger.messengerclient"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.messenger.messengerclient"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Базовые Android зависимости
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("com.google.code.gson:gson:2.10.1")

    // SwipeRefreshLayout (если нужен)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Retrofit для HTTP запросов
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // 2. Добавьте зависимости для Firebase и WorkManager
    // Firebase BOM (Bill of Materials) управляет версиями
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    // Firebase Cloud Messaging (FCM)
    implementation("com.google.firebase:firebase-messaging-ktx")
    // Firebase Analytics (опционально, но часто включается с BOM)
    implementation("com.google.firebase:firebase-analytics-ktx")
    // WorkManager для фоновых задач
    implementation("androidx.work:work-runtime-ktx:2.9.0")

//    implementation("dev.onvoid.webrtc:webrtc-java:0.7.0")
// https://mvnrepository.com/artifact/com.cloudflare.realtimekit/webrtc-android
    implementation("com.cloudflare.realtimekit:webrtc-android:137.7151.10.2")


    // Тестирование
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}