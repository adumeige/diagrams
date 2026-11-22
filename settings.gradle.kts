rootProject.name = "diagrams"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "model",
    "assets",
    "processor",
    "resolver",
    "lint",
    "harness",
    "render-svg",
    "render-raster",
    "cli",
    "fixtures",
)
