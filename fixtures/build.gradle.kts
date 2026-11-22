plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(project(":lint"))
    testImplementation(project(":render-svg"))
    testImplementation(project(":render-raster"))
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    systemProperty("diagrams.fixtures", layout.projectDirectory.dir("cases").asFile.absolutePath)
    // DIAGRAMS_UPDATE=1 ./gradlew :fixtures:test regenerates expected files (spec section 19).
    environment("DIAGRAMS_UPDATE", System.getenv("DIAGRAMS_UPDATE") ?: "")
    inputs.dir("cases")
    inputs.dir("../examples")
    inputs.dir("../libs")
    inputs.dir("../themes")
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
