plugins {
    kotlin("jvm")
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
}

val mps by configurations.creating



dependencies {
    // stdlib needs to match the kotlin version supported by the platform see
    // https://plugins.jetbrains.com/docs/intellij/kotlin.html#kotlin-standard-library
    // We are targeting MPS 2020.3 and up right now, so the max version we can depend on is 1.4.0.
    implementation(kotlin("stdlib:1.4.0"))
    implementation(project(":api"))
    implementation("org.slf4j:slf4j-api:1.7.35")
    compileOnly("com.jetbrains:mps-openapi:$mps_version")
    compileOnly("com.jetbrains:mps-core:$mps_version")
    compileOnly("com.jetbrains:mps-modelchecker:$mps_version")
    compileOnly("com.jetbrains:mps-project-check:$mps_version")
    compileOnly("com.jetbrains:mps-platform:$mps_version")
    compileOnly("com.jetbrains:platform-api:$mps_version")
    compileOnly("com.jetbrains:extensions:$mps_version")
    compileOnly("com.jetbrains:util:$mps_version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    mps("com.jetbrains:mps:$mps_version")
    //testImplementation("org.slf4j:api:1.7.35")
    testImplementation(files( mps.resolve().flatMap { zipTree(it).files }))
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "11"
        apiVersion = "1.4"
    }
}