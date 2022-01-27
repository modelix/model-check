plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    java
}

group = "org.modelix.model-check"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // stdlib needs to match the kotlin version supported by the platform see
    // https://plugins.jetbrains.com/docs/intellij/kotlin.html#kotlin-standard-library
    // We are targeting MPS 2020.3 and up right now, so the max version we can depend on is 1.4.0.
    implementation(kotlin("stdlib:1.4.0"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.3.2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "11"
        apiVersion = "1.4"
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}