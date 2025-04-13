plugins {
    kotlin("jvm") version "2.0.21"
}

group = "com.iainschmitt.januaryplaygroundbackend"
version = "unspecified"
val ktor_version = "2.3.7"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("io.arrow-kt:arrow-core:2.0.1")
    implementation("io.javalin:javalin:6.2.0")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
}
// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}



tasks.test {
    useJUnitPlatform()
}