pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins { id("org.jetbrains.kotlin.jvm") version "2.2.21" }
}

dependencyResolutionManagement { repositories { mavenCentral() } }

rootProject.name = "capri-host"
include(":agent-core")
project(":agent-core").projectDir = file("../agent-core")
