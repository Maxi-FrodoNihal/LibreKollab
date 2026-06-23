package org.msc.liberekollab.adapter.http

import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.server.application.*
import io.ktor.server.application.install
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import org.msc.liberekollab.domain.port.IOAPI
import org.msc.liberekollab.domain.port.KollabAPI
import org.msc.liberekollab.adapter.http.controller.IOController
import org.msc.liberekollab.adapter.http.controller.KollabController
import org.slf4j.LoggerFactory

class KtorServer(
    private val io: IOAPI,
    private val kollab: KollabAPI
) {

    private val log = LoggerFactory.getLogger(KtorServer::class.java)
    private var engine: EmbeddedServer<*, *>? = null

    fun start(port: Int) {
        log.info("Starting LibereKollab on http://0.0.0.0:$port")
        engine = embeddedServer(Netty, port = port) { configure() }
            .apply { start(wait = false) }
    }

    fun stop() {
        engine?.stop(0, 0)
        engine = null
    }

    fun Application.configure() {
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
            get("swagger") { call.respondRedirect("/swagger/index.html") }
            route("swagger") { swaggerUI("/api.json") }
            KollabController(kollab).registerRoutes(this)
            IOController(io).registerRoutes(this)
        }
    }
}
