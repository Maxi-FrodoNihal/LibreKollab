package org.msc.liberekollab

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import kotlinx.serialization.Serializable
import org.msc.liberekollab.controller.DocumentController
import org.msc.liberekollab.controller.KollabController
import org.msc.liberekollab.storage.MinioStorage

@Serializable
data class HelloResponse(val message: String)

fun main() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json()
        }
        routing {
            KollabController().registerRoutes(this)
            DocumentController(MinioStorage()).registerRoutes(this)
        }
    }.start(wait = true)
}
