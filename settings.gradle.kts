pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins {
    // Provision a full compiler toolchain when only a Java runtime is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "Intentional"
include(":shared", ":androidApp")
