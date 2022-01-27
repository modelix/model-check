plugins {
    id("com.specificlanguages.mps")
    `maven-publish`
}

repositories {
    mavenCentral()
    maven { url = uri("https://projects.itemis.de/nexus/content/repositories/mbeddr") }
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
}

dependencies {
    mps("com.jetbrains:mps:2020.3.6")
}

stubs {
    register("stubs") {
        destinationDir("$projectDir/solutions/org.modelix.modelcheck.mps.plugin/lib")
        dependency(project(":mps-integration"))
    }
}

publishing {
    publications {
        register<MavenPublication>("mpsPlugin") {
            from(components["mps"])

            // Put resolved versions of dependencies into POM files
            versionMapping { usage("java-runtime") { fromResolutionOf("generation") } }
        }
    }
}