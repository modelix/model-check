plugins {
    id("com.github.node-gradle.node")
}

group = "org.modelix.model-check"
version = "1.0-SNAPSHOT"

val buildTsc by tasks.creating(com.github.gradle.node.npm.task.NpxTask::class) {
    dependsOn(tasks.getByName("yarn_install"))
    command.set("build")
}

tasks.create("build") {
    dependsOn(buildTsc)
}