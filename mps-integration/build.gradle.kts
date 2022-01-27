plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    java
}

group = "org.modelix.model-check"
version = "1.0-SNAPSHOT"

val mps_version: String by project


repositories {
    mavenCentral()
    maven {
        url = uri("https://projects.itemis.de/nexus/content/repositories/mbeddr")
    }
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
}

val mps by configurations.creating


dependencies {
    mps("com.jetbrains:mps:$mps_version")
    // stdlib needs to match the kotlin version supported by the platform see
    // https://plugins.jetbrains.com/docs/intellij/kotlin.html#kotlin-standard-library
    // We are targeting MPS 2020.3 and up right now, so the max version we can depend on is 1.4.0.
    compileOnly(kotlin("stdlib:1.4.0"))
    implementation(project(":core"))
    implementation(project(":api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.7.2")
    // Netty is provided by the platform, and are available at runtime implicitly.
    // The versions here need to match the ones provided by the platform to prevent runtime errors.
    compileOnly("io.netty:netty-all:4.1.52.Final")
    compileOnly("com.jetbrains:mps-openapi:$mps_version")
    compileOnly("com.jetbrains:mps-core:$mps_version")
    compileOnly(files( mps.resolve().flatMap { zipTree(it).files }))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
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