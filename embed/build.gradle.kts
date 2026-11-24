import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("multiplatform")
    id("com.gradleup.shadow")
}

// Embeddable distributions of the core, for hosts that are not Gradle builds: it packs the harness
// (and, on the JVM, the raster renderer). `./gradlew :embed:dist` writes both into embed/build/embed:
//   jvm/diagrams-embed-<version>.jar  one jar, Kotlin stdlib relocated under diagrams.shaded.kotlin
//   js/                               one self-contained ES module, its .d.mts and a package.json
kotlin {
    js {
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":harness"))
        }
        jvmMain.dependencies {
            api(project(":render-raster"))
        }
    }
}

val shadedJar = tasks.register<ShadowJar>("shadedJar") {
    group = "distribution"
    description = "Builds one JVM jar with the core, the raster renderer and a relocated Kotlin stdlib."
    archiveBaseName.set("diagrams-embed")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("embed/jvm"))
    val jvm = kotlin.jvm().compilations.getByName("main")
    from(jvm.output.allOutputs)
    configurations.set(listOf(project.configurations.getByName(jvm.runtimeDependencyConfigurationName)))
    relocate("kotlin", "diagrams.shaded.kotlin")
    relocate("org.jetbrains.annotations", "diagrams.shaded.org.jetbrains.annotations")
    relocate("org.intellij.lang.annotations", "diagrams.shaded.org.intellij.lang.annotations")
    mergeServiceFiles()
    exclude("META-INF/maven/**", "META-INF/*.kotlin_module", "META-INF/versions/9/module-info.class", "module-info.class")
    manifest {
        attributes("Implementation-Title" to "diagrams", "Implementation-Version" to project.version)
    }
}

val jsBundle = tasks.register<Sync>("jsBundle") {
    group = "distribution"
    description = "Builds one self-contained ES module of the harness, with TypeScript definitions."
    from(tasks.named("jsNodeProductionLibraryDistribution"))
    into(layout.buildDirectory.dir("embed/js"))
}

tasks.register("dist") {
    group = "distribution"
    description = "Builds the shaded JVM jar and the JS bundle into build/embed."
    dependsOn(shadedJar, jsBundle)
}
