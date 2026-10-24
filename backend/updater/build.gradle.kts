// Windows server-updater: a small JDK-only service (couple-updater) that replaces deploy/updater/updater.py on
// hosts without Docker. Same webhook/HMAC scheme; deploys release zips instead of images.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "com.qimingnan.couple"
version = rootProject.version

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.qimingnan.couple.updater.MainKt")
    applicationName = "couple-updater"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version.toString()) }
}
