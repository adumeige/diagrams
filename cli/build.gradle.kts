plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":lint"))
    implementation(project(":render-svg"))
    implementation(project(":render-raster"))
}

application {
    applicationName = "diagrams"
    mainClass.set("diagrams.cli.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

// The live viewer (`diagrams serve`) runs the core in the browser: the harness compiled to ES
// modules ships inside the CLI as resources.
val viewerLib = tasks.register<Copy>("viewerLib") {
    dependsOn(":harness:jsProductionLibraryCompileSync")
    from(project(":harness").layout.buildDirectory.dir("compileSync/js/main/productionLibrary/kotlin")) {
        include("*.mjs")
    }
    into(layout.buildDirectory.dir("generated/viewer/viewer/lib"))
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/viewer"))
}

tasks.processResources {
    dependsOn(viewerLib)
}
