plugins {
    application
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":agent-core"))
    implementation("org.json:json:20240303")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("com.microsoft.playwright:playwright:1.61.0")
    testImplementation("junit:junit:4.13.2")
}

application { mainClass.set("ai.capri.host.MainKt") }

tasks.processResources {
    doFirst {
        // Drop stale generated rules before copying the current source set.
        delete(destinationDir.resolve("kernel-rules"))
    }
    from(rootProject.file("../tools/web")) {
        include("style.css", "markdown.js")
        into("web")
    }
}

project(":agent-core").layout.buildDirectory.set(layout.buildDirectory.dir("agent-core"))
