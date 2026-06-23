package org.msc.liberekollab.adapter.http.controller

import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.msc.liberekollab.domain.port.KollabAPI
import org.msc.liberekollab.domain.model.change.ChangeStatus
import org.msc.liberekollab.domain.model.Comment
import org.msc.liberekollab.adapter.http.request.AddCommentRequest
import org.msc.liberekollab.adapter.http.request.EditTextRequest
import org.msc.liberekollab.adapter.http.request.UpdateCommentRequest
import org.msc.liberekollab.adapter.http.response.ChangesResponse
import org.msc.liberekollab.adapter.http.response.ChaptersResponse
import org.msc.liberekollab.adapter.http.response.CommentsResponse
import org.msc.liberekollab.adapter.http.response.EditModeResponse
import org.msc.liberekollab.adapter.http.response.PageCountResponse
import org.msc.liberekollab.adapter.http.response.TextResponse

class KollabController(private val kollabAPI: KollabAPI) {

    fun registerRoutes(routing: Routing) {
        routing.route("/kollab") {
            get("/health", {
                tags = listOf("Kollab")
                summary = "Health check"
                response { code(HttpStatusCode.OK) { description = "Service is healthy" } }
            }) { healthCheck() }

            get("/text/{documentId}", {
                tags = listOf("Kollab")
                summary = "Get full document text"
                request {
                    pathParameter<String>("documentId") { }
                    queryParameter<String>("changeStatus") {
                        description = "BEFORE (original), FUSION (default, raw), AFTER (changes applied)"
                        required = false
                    }
                }
                response { code(HttpStatusCode.OK) { body<TextResponse>() } }
            }) { getText() }

            patch("/text/{documentId}", {
                tags = listOf("Kollab")
                summary = "Edit a text range (creates a tracked change)"
                request {
                    pathParameter<String>("documentId") { }
                    body<EditTextRequest>()
                }
                response {
                    code(HttpStatusCode.OK) { description = "Text edited" }
                    code(HttpStatusCode.BadRequest) { description = "Invalid anchor" }
                }
            }) { editText() }

            get("/text/{documentId}/changes", {
                tags = listOf("Kollab")
                summary = "List all tracked changes"
                request { pathParameter<String>("documentId") { } }
                response { code(HttpStatusCode.OK) { body<ChangesResponse>() } }
            }) { getChanges() }

            get("/text/{documentId}/pagecount", {
                tags = listOf("Kollab")
                summary = "Get number of pages"
                request { pathParameter<String>("documentId") { } }
                response { code(HttpStatusCode.OK) { body<PageCountResponse>() } }
            }) { getPageCount() }

            get("/text/{documentId}/chapters", {
                tags = listOf("Kollab")
                summary = "List chapter headings"
                request { pathParameter<String>("documentId") { } }
                response { code(HttpStatusCode.OK) { body<ChaptersResponse>() } }
            }) { getChapters() }

            get("/text/{documentId}/pages/{fromPage}/{toPage}", {
                tags = listOf("Kollab")
                summary = "Get text of a page range"
                request {
                    pathParameter<String>("documentId") { }
                    pathParameter<Int>("fromPage") { }
                    pathParameter<Int>("toPage") { }
                    queryParameter<String>("changeStatus") { required = false }
                }
                response { code(HttpStatusCode.OK) { body<TextResponse>() } }
            }) { getTextByPages() }

            get("/text/{documentId}/chapters/{chapter}", {
                tags = listOf("Kollab")
                summary = "Get text of a chapter"
                request {
                    pathParameter<String>("documentId") { }
                    pathParameter<String>("chapter") { }
                    queryParameter<String>("changeStatus") { required = false }
                }
                response { code(HttpStatusCode.OK) { body<TextResponse>() } }
            }) { getTextByChapter() }

            get("/text/{documentId}/editmode", {
                tags = listOf("Kollab")
                summary = "Check if Track Changes is active"
                request { pathParameter<String>("documentId") { } }
                response { code(HttpStatusCode.OK) { body<EditModeResponse>() } }
            }) { getEditMode() }

            get("/text/{documentId}/comments", {
                tags = listOf("Comments")
                summary = "List all comments"
                request { pathParameter<String>("documentId") { } }
                response { code(HttpStatusCode.OK) { body<CommentsResponse>() } }
            }) { getComments() }

            get("/text/{documentId}/comments/{commentId}", {
                tags = listOf("Comments")
                summary = "Get a single comment by ID"
                request {
                    pathParameter<String>("documentId") { }
                    pathParameter<String>("commentId") { }
                }
                response {
                    code(HttpStatusCode.OK) { body<Comment>() }
                    code(HttpStatusCode.NotFound) { description = "Comment not found" }
                }
            }) { getComment() }

            post("/text/{documentId}/comments", {
                tags = listOf("Comments")
                summary = "Add a comment"
                request {
                    pathParameter<String>("documentId") { }
                    body<AddCommentRequest>()
                }
                response { code(HttpStatusCode.NoContent) { description = "Comment added" } }
            }) { addComment() }

            patch("/text/{documentId}/comments/{commentId}", {
                tags = listOf("Comments")
                summary = "Update comment text"
                request {
                    pathParameter<String>("documentId") { }
                    pathParameter<String>("commentId") { }
                    body<UpdateCommentRequest>()
                }
                response {
                    code(HttpStatusCode.OK) { description = "Comment updated" }
                    code(HttpStatusCode.NotFound) { description = "Comment not found" }
                }
            }) { updateComment() }

            delete("/text/{documentId}/comments/{commentId}", {
                tags = listOf("Comments")
                summary = "Delete a comment"
                request {
                    pathParameter<String>("documentId") { }
                    pathParameter<String>("commentId") { }
                }
                response { code(HttpStatusCode.NoContent) { description = "Comment deleted" } }
            }) { deleteComment() }
        }
    }

    private fun RoutingContext.changeStatus(): ChangeStatus =
        call.request.queryParameters["changeStatus"]
            ?.let { runCatching { ChangeStatus.valueOf(it.uppercase()) }.getOrNull() }
            ?: ChangeStatus.FUSION

    private suspend fun RoutingContext.healthCheck() {
        call.respond("healthy")
    }

    private suspend fun RoutingContext.editText() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val request = call.receive<EditTextRequest>()
        try {
            kollabAPI.editText(documentId, request.anchor, request.newText)
            call.respond(HttpStatusCode.OK)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest)
        }
    }

    private suspend fun RoutingContext.getChanges() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val changes = kollabAPI.getChanges(documentId)
        call.respond(HttpStatusCode.OK, ChangesResponse(changes))
    }

    private suspend fun RoutingContext.getText() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val text = kollabAPI.getText(documentId, changeStatus())
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
        val text = kollabAPI.getTextByPages(documentId, fromPage, toPage, changeStatus())
        call.respond(HttpStatusCode.OK, TextResponse(text))
    }

    private suspend fun RoutingContext.getTextByChapter() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val chapter = verifyForBadRequest("chapter") ?: return
        val text = kollabAPI.getTextByChapter(documentId, chapter, changeStatus())
        call.respond(HttpStatusCode.OK, TextResponse(text))
    }

    private suspend fun RoutingContext.getEditMode() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val active = kollabAPI.getEditMode(documentId)
        call.respond(HttpStatusCode.OK, EditModeResponse(active))
    }

    private suspend fun RoutingContext.getComments() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val comments = kollabAPI.getComments(documentId)
        call.respond(HttpStatusCode.OK, CommentsResponse(comments))
    }

    private suspend fun RoutingContext.getComment() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val commentId = verifyForBadRequest("commentId") ?: return
        val comment = kollabAPI.getComment(documentId, commentId)
        if (comment == null) call.respond(HttpStatusCode.NotFound)
        else call.respond(HttpStatusCode.OK, comment)
    }

    private suspend fun RoutingContext.addComment() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val request = call.receive<AddCommentRequest>()
        kollabAPI.addComment(documentId, request.commentText, request.author, request.anchor)
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun RoutingContext.updateComment() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val commentId = verifyForBadRequest("commentId") ?: return
        val request = call.receive<UpdateCommentRequest>()
        try {
            kollabAPI.updateComment(documentId, commentId, request.newText)
            call.respond(HttpStatusCode.OK)
        } catch (e: NoSuchElementException) {
            call.respond(HttpStatusCode.NotFound)
        }
    }

    private suspend fun RoutingContext.deleteComment() {
        val documentId = verifyForBadRequest("documentId") ?: return
        val commentId = verifyForBadRequest("commentId") ?: return
        kollabAPI.deleteComment(documentId, commentId)
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun RoutingContext.verifyForBadRequest(paramName: String): String? {
        val value = call.parameters[paramName]
        if (value == null) call.respond(HttpStatusCode.BadRequest)
        return value
    }
}
