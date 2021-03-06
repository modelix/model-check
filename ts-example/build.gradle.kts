plugins {
    id("com.github.node-gradle.node")
}

group = "org.modelix.model-check"
version = "1.0-SNAPSHOT"


val runWebpack by tasks.creating(com.github.gradle.node.npm.task.NpxTask::class) {
    dependsOn(tasks.getByName("yarn_install"))
    command.set("webpack")
}

tasks.create("build") {
    dependsOn(runWebpack)
}
