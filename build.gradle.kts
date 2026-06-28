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
    compileOnly(files("$unoLibPath/libreoffice.jar"))
    testImplementation(files("$unoLibPath/libreoffice.jar"))

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
    implementation("io.modelcontextprotocol:kotlin-sdk:0.13.0")

    testImplementation(kotlin("test"))
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

val fatJar by tasks.registering(Jar::class) {
    archiveBaseName.set("liberekollab-all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") && !it.name.contains("libreoffice") }
            .map { zipTree(it) }
    })
    manifest {
        attributes["Implementation-Title"] = "LibereKollab"
        attributes["Implementation-Version"] = version
    }
}

val oxt by tasks.registering(Zip::class) {
    group = "build"
    description = "Packages the LibreOffice extension (.oxt)"
    archiveBaseName.set("LibereKollab")
    archiveExtension.set("oxt")
    destinationDirectory.set(layout.buildDirectory.dir("oxt"))
    dependsOn(fatJar)
    from(fatJar) { rename { "liberekollab-all.jar" } }
    from("oxt/META-INF") { into("META-INF") }
    from("oxt") { include("*.components", "*.xcu", "*.xml", "*.txt") }
    from("oxt/dialogs") { into("dialogs") }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("testcontainers.reuse.enable", "true")
}
