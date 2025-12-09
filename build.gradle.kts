import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
    maven("https://repo.menthamc.org/repository/maven-public/")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

kotlin {
    jvmToolchain(17)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("harebell")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    minimize()
    manifest {
        attributes["Main-Class"] = "dev.menthamc.harebell.CliMainKt"
    }
}
