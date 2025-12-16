// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // 1. Обязательно должен быть здесь:
        classpath("com.google.gms:google-services:4.4.0")

        // Другие classpath зависимости, если есть
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // 2. Дополнительный плагин НЕ нужен здесь
}