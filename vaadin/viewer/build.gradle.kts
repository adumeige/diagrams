plugins {
    kotlin("jvm")
    `java-library`
}

base.archivesName.set("diagrams-vaadin-viewer")

// Vaadin bindings for the viewer: the `<diagrams-viewer>` element runs the core in the browser and
// the DiagramViewer component drives it from the server. Vaadin is provided by the application.
dependencies {
    api(project(":harness"))
    compileOnly(platform("com.vaadin:vaadin-bom:25.3.6"))
    compileOnly("com.vaadin:flow-server")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")

    testImplementation(platform("com.vaadin:vaadin-bom:25.3.6"))
    testImplementation("com.vaadin:flow-server")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.1.0")
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The core as one ES module, next to the element that imports it: Vaadin's frontend build picks up
// META-INF/frontend from every jar on the class path.
val embedModule = tasks.register<Copy>("embedModule") {
    from(project(":embed").tasks.named("jsBundle")) {
        include("diagrams-embed.mjs")
    }
    into(layout.buildDirectory.dir("generated/embed/META-INF/frontend/diagrams-viewer"))
}

sourceSets.main {
    resources.srcDir(embedModule.map { layout.buildDirectory.dir("generated/embed").get() })
}

tasks.test {
    useJUnitPlatform()
    systemProperty("diagrams.root", rootProject.layout.projectDirectory.asFile.absolutePath)
}
