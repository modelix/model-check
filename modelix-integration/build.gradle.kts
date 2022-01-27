plugins {
    kotlin("jvm")
}

group = "org.modelix.model-check"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.eclipse.jetty:jetty-servlet:9.4.20.v20190813")
    implementation("org.eclipse.jetty:jetty-server:9.4.20.v20190813")
    implementation("org.eclipse.jetty.websocket:websocket-servlet:9.4.20.v20190813")

}