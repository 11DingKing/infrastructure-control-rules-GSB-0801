plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("io.ktor.plugin") version "2.3.12"
    application
}

group = "com.infra"
version = "1.0.0"

application {
    mainClass.set("com.infra.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:${property("ktor.version")}")
    implementation("io.ktor:ktor-server-netty-jvm:${property("ktor.version")}")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:${property("ktor.version")}")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:${property("ktor.version")}")
    implementation("io.ktor:ktor-server-status-pages-jvm:${property("ktor.version")}")
    implementation("io.ktor:ktor-server-cors-jvm:${property("ktor.version")}")
    implementation("io.ktor:ktor-server-call-logging-jvm:${property("ktor.version")}")

    implementation("org.jetbrains.exposed:exposed-core:${property("exposed.version")}")
    implementation("org.jetbrains.exposed:exposed-dao:${property("exposed.version")}")
    implementation("org.jetbrains.exposed:exposed-jdbc:${property("exposed.version")}")
    implementation("org.jetbrains.exposed:exposed-java-time:${property("exposed.version")}")

    implementation("org.xerial:sqlite-jdbc:${property("sqlite.version")}")
    implementation("com.zaxxer:HikariCP:5.1.0")

    implementation("ch.qos.logback:logback-classic:${property("logback.version")}")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-tests-jvm:${property("ktor.version")}")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
