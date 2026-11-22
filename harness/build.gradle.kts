plugins {
    kotlin("multiplatform")
}

// The harness API (spec section 17) in common code; the JS target is an ES module library that the
// live viewer loads in the browser (spec section 16: the browser target comes with the harness API).
kotlin {
    js {
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":lint"))
            api(project(":render-svg"))
        }
    }
}
