plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "1.9.10"
}

group = "com.iainschmitt.januaryplaygroundbackend"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.kafka:kafka-clients:3.5.0")
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("io.arrow-kt:arrow-core:2.0.1")
    implementation(project(":shared"))
    implementation("org.slf4j:slf4j-simple:2.0.9")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}