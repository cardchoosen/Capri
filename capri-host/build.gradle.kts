plugins {
    application
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":agent-core"))
    implementation("org.json:json:20240303")
}

application { mainClass.set("ai.capri.host.MainKt") }

tasks.processResources {
    from(rootProject.file("../tools/web")) {
        include("style.css", "markdown.js")
        into("web")
    }
}

project(":agent-core").layout.buildDirectory.set(layout.buildDirectory.dir("agent-core"))
