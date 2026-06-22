package org.msc.liberekollab

import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.server.application.install
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import org.msc.liberekollab.controller.IOController
import org.msc.liberekollab.controller.KollabController
import org.msc.liberekollab.storage.MinioObject

fun main() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { json() }
        install(OpenApi) {
            info {
                title = "LibereKollab API"
                version = "1.0"
                description = "LibreOffice document editing via UNO as an AI-usable interface."
            }
        }
        routing {
            route("api.json") { openApi() }
            route("swagger") { swaggerUI("/api.json") }
            KollabController().registerRoutes(this)
            IOController(MinioObject()).registerRoutes(this)
        }
    }.start(wait = true)
}
