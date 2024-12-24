plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
}

kotlin {
     jvmToolchain(21)

    dependencies {
        implementation("io.ktor:ktor-client-core:3.0.3")
        implementation("io.ktor:ktor-client-cio:3.0.3")
        implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
        implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
        implementation("io.ktor:ktor-client-auth:3.0.3")
        implementation("com.google.auth:google-auth-library-oauth2-http:1.30.1")
        implementation("com.google.auth:google-auth-library-credentials:1.30.1")
        runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
    }
}

application {
    mainClass = "MainKt"
}
