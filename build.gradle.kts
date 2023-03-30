plugins {
    id("java")
    kotlin("jvm") version("1.6.10")
    id("su.plo.voice.plugin") version("1.0.0")
}

group = "su.plo"
version = "1.0.0"

dependencies {
    compileOnly("su.plo.voice.api:server:2.0.0+ALPHA")

    annotationProcessor("org.projectlombok:lombok:+")
}

tasks {
    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(8))
    }
}
