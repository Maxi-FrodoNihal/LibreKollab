plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

group = "org.msc.librekollab"
version = "1.0.0"

kotlin {
    jvmToolchain(25)
}

val ktorVersion = "3.5.1"
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
    implementation("ch.qos.logback:logback-classic:1.5.37")

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
    implementation("io.modelcontextprotocol:kotlin-sdk:0.13.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.4")

    testImplementation(kotlin("test"))
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:$testcontainersVersion")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("io.mockk:mockk:1.14.11")
}

val fatJar by tasks.registering(Jar::class) {
    archiveBaseName.set("librekollab-all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") && !it.name.contains("libreoffice") }
            .map { zipTree(it) }
    })
    manifest {
        attributes["Implementation-Title"] = "LibreKollab"
        attributes["Implementation-Version"] = version
        attributes["RegistrationClassName"] = "org.msc.librekollab.adapter.libreoffice.plugin.LibreKollabPlugin"
    }
}

val oxt by tasks.registering(Zip::class) {
    group = "build"
    description = "Packages the LibreOffice extension (.oxt)"
    archiveBaseName.set("LibreKollab")
    archiveExtension.set("oxt")
    destinationDirectory.set(layout.buildDirectory.dir("oxt"))
    dependsOn(fatJar)
    from(fatJar) { rename { "librekollab-all.jar" } }
    from("oxt/META-INF") { into("META-INF") }
    from("oxt") { include("*.components", "*.xcu", "*.xml", "*.txt") }
    from("oxt/dialogs") { into("dialogs") }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
