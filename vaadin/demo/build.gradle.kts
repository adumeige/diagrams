plugins {
    kotlin("jvm")
    application
    id("com.vaadin")
}

// A demo of the Vaadin bindings: the examples of this repository in an editor beside the viewer,
// served by Vaadin Boot (embedded Jetty). `./gradlew :vaadin:demo:run`, then http://localhost:8080
dependencies {
    implementation(project(":vaadin:karibu"))
    implementation(platform("com.vaadin:vaadin-bom:25.3.6"))
    implementation("com.vaadin:vaadin-core")
    implementation("com.github.mvysny.vaadin-boot:vaadin-boot:13.8")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    // dev mode: builds the frontend bundle with the viewer element on first start
    runtimeOnly("com.vaadin:vaadin-dev")
}

application {
    mainClass.set("diagrams.vaadin.demo.MainKt")
}

// The repository's examples, with the libraries, themes and fonts they import, as class path
// resources under diagrams/, and an index of the examples.
val documents = tasks.register<Sync>("documents") {
    val repo = rootProject.layout.projectDirectory
    into(layout.buildDirectory.dir("generated/documents/diagrams"))
    from(repo.dir("examples")) { into("examples") }
    from(repo.dir("libs")) { into("libs") }
    from(repo.dir("themes")) { into("themes") }
    from(repo.dir("fonts")) { into("fonts") }
    val examples = repo.dir("examples").asFile
    inputs.dir(examples)
    doLast {
        val names = examples.listFiles { f -> f.name.endsWith(".yaml") }.orEmpty().map { it.name }.sorted()
        destinationDir.resolve("examples/index.txt").writeText(names.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main {
    resources.srcDir(documents.map { layout.buildDirectory.dir("generated/documents").get() })
}

// Dev mode needs the frontend prepared (package.json, index.html, the build-info token) before the
// application starts.
tasks.processResources {
    dependsOn("vaadinPrepareFrontend")
}

vaadin {
    // Flow views only: no React router in the frontend bundle
    reactEnable = false
}
