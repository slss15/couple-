plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
}

group = "com.qimingnan.couple"
// CI passes -PappVersion=X.Y.Z (tags) or edge-<sha> (main); local builds stay 0.1.0.
version = (findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0"

val ktorVersion = "3.1.3"
val exposedVersion = "0.61.0"
val flywayVersion = "11.8.2"
// Must match the Netty that ktor-server-netty brings, so Moquette's netty-codec-mqtt is aligned with it.
val nettyVersion = "4.1.119.Final"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.qimingnan.couple.backend.ApplicationKt")
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-rate-limit:$ktorVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-mysql:$flywayVersion")
    runtimeOnly("com.mysql:mysql-connector-j:9.3.0")

    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.github.f4b6a3:ulid-creator:5.2.3")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Embedded MQTT broker (MQTT_BROKER_MODE=embedded, the Windows-native default).
    implementation(platform("io.netty:netty-bom:$nettyVersion"))
    implementation("io.moquette:moquette-broker:0.17") {
        // Logging goes through logback; metrics/error-reporting integrations are never enabled.
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "ch.qos.reload4j")
        exclude(group = "com.bugsnag")
        exclude(group = "com.librato.metrics")
        exclude(group = "io.dropwizard.metrics")
        exclude(group = "com.zaxxer", module = "HikariCP")
    }

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("version.properties") {
        expand("version" to v)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
