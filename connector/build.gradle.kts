plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

group = "ink.easycode.qqclaw"
version = "0.1.0"
description = "Obisidian easycode QQClaw connector"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("ink.easycode.qqclaw.AppKt")
    applicationName = "ink.easycode.qqclaw.connector"
}

tasks.test {
    useJUnitPlatform()
}
