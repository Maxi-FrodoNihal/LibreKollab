plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "org.msc.liberekollab"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(25)
}

val ktorVersion = "3.1.3"
val testcontainersVersion = "2.0.5"
val unoLibPath = "libs/uno"

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("$unoLibPath/libreoffice.jar"))

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.github.smiley4:ktor-openapi:5.0.0")
    implementation("io.github.smiley4:ktor-swagger-ui:5.0.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("io.minio:minio:9.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:$testcontainersVersion")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

application {
    mainClass.set("org.msc.liberekollab.MainKt")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
    )
}

tasks.test {
    useJUnitPlatform()
}
