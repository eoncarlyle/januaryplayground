plugins {
    kotlin("jvm") version "2.0.21"
}

group = "com.iainschmitt.januaryplaygroundbackend"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("io.arrow-kt:arrow-core:2.0.1")
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