plugins {
    id("java")
    kotlin("jvm") version(libs.versions.kotlin.get())
    alias(libs.plugins.pv.entrypoints)
    alias(libs.plugins.pv.java.templates)
}

dependencies {
    compileOnly(libs.pv)
    annotationProcessor(libs.lombok)
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.plasmoverse.com/snapshots")
    maven("https://repo.plasmoverse.com/releases")
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
    }

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(8))
    }
}
