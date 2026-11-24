import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("multiplatform") version "2.3.0" apply false
    kotlin("jvm") version "2.3.0" apply false
    id("com.gradleup.shadow") version "9.2.2" apply false
    id("com.vaadin") version "25.3.6" apply false
}

allprojects {
    group = "diagrams"
    version = "0.1.0-SNAPSHOT"
}

// Portable core modules: JVM first, JS compiled from milestone 0 (spec section 16).
subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvm {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
            }
            js {
                nodejs()
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
            }
        }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}
