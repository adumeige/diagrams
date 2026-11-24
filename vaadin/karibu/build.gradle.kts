plugins {
    kotlin("jvm")
    `java-library`
}

base.archivesName.set("diagrams-vaadin-karibu")

// The viewer as a karibu-dsl component: `diagramViewer { }` builders, `onXxx { }` listeners and
// StateFlow bindings, for Kotlin applications built with karibu-dsl.
dependencies {
    api(project(":vaadin:viewer"))
    api("com.github.mvysny.karibudsl:karibu-dsl:2.6.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    compileOnly(platform("com.vaadin:vaadin-bom:25.3.6"))
    compileOnly("com.vaadin:vaadin-core")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")

    testImplementation(platform("com.vaadin:vaadin-bom:25.3.6"))
    testImplementation("com.vaadin:vaadin-core")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.1.0")
    testImplementation("com.github.mvysny.kaributesting:karibu-testing-v24:2.7.3")
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
