package org.msc.liberekollab.controller

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.msc.liberekollab.LibereKollab
import org.msc.liberekollab.abstrakt.KollabAPI
import org.msc.liberekollab.request.AddCommentRequest
import org.msc.liberekollab.response.ChaptersResponse
import org.msc.liberekollab.response.CommentsResponse
import org.msc.liberekollab.response.EditModeResponse
import org.msc.liberekollab.response.PageCountResponse
import org.msc.liberekollab.response.TextResponse

class KollabController {

    private val kollabAPI: KollabAPI = LibereKollab()

    fun registerRoutes(routing: Routing) {
        routing.route("/kollab") {
            get("/health") { healthCheck() }
            get("/text/{documentId}") { getText() }
            get("/text/{documentId}/pagecount") { getPageCount() }
            get("/text/{documentId}/chapters") { getChapters() }
            get("/text/{documentId}/pages/{fromPage}/{toPage}") { getTextByPages() }
            get("/text/{documentId}/chapters/{chapter}") { getTextByChapter() }
            get("/text/{documentId}/comments") { getComments() }
            post("/text/{documentId}/comments") { addComment() }
            get("/text/{documentId}/editmode") { getEditMode() }
        }
    }

    private suspend fun RoutingContext.healthCheck() {
        call.respond("healthy")
    }

    private suspend fun RoutingContext.getText() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val text = kollabAPI.getText(documentId)
        call.respond(HttpStatusCode.OK, TextResponse(text))
    }

    private suspend fun RoutingContext.getPageCount() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val pageCount = kollabAPI.getPageCount(documentId)
        call.respond(HttpStatusCode.OK, PageCountResponse(pageCount))
    }

    private suspend fun RoutingContext.getChapters() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val chapters = kollabAPI.getChapters(documentId)
        call.respond(HttpStatusCode.OK, ChaptersResponse(chapters))
    }

    private suspend fun RoutingContext.getTextByPages() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val fromPage = verifyForBadRequest("fromPage")?.toIntOrNull() ?: return call.respond(HttpStatusCode.BadRequest)
        val toPage = verifyForBadRequest("toPage")?.toIntOrNull() ?: return call.respond(HttpStatusCode.BadRequest)
        val text = kollabAPI.getTextByPages(documentId, fromPage, toPage)
        call.respond(HttpStatusCode.OK, TextResponse(text))
    }

    private suspend fun RoutingContext.getTextByChapter() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val chapter = verifyForBadRequest("chapter") ?: return
        val text = kollabAPI.getTextByChapter(documentId, chapter)
        call.respond(HttpStatusCode.OK, TextResponse(text))
    }

    private suspend fun RoutingContext.getEditMode() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val active = kollabAPI.getEditMode(documentId)
        call.respond(HttpStatusCode.OK, EditModeResponse(active))
    }

    private suspend fun RoutingContext.addComment() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val request = call.receive<AddCommentRequest>()
        kollabAPI.addComment(documentId, request.commentText, request.author, request.anchor)
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun RoutingContext.getComments() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val comments = kollabAPI.getComments(documentId)
        call.respond(HttpStatusCode.OK, CommentsResponse(comments))
    }

    private suspend fun RoutingContext.verifyForBadRequest(paramName: String): String? {
        val value = call.parameters[paramName]
        if (value == null) call.respond(HttpStatusCode.BadRequest)
        return value
    }
}
