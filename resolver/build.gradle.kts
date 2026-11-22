plugins {
    kotlin("multiplatform")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":processor"))
            api(project(":assets"))
        }
    }
}
