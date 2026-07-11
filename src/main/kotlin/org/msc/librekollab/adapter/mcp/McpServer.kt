package org.msc.librekollab.adapter.mcp

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.msc.librekollab.adapter.mcp.request.AddCommentRequest
import org.msc.librekollab.adapter.mcp.request.DocumentAndCommentIdRequest
import org.msc.librekollab.adapter.mcp.request.DocumentIdRequest
import org.msc.librekollab.adapter.mcp.request.EditTextRequest
import org.msc.librekollab.adapter.mcp.request.GetImageRequest
import org.msc.librekollab.adapter.mcp.request.GetTextByChapterRequest
import org.msc.librekollab.adapter.mcp.request.GetTextByPagesRequest
import org.msc.librekollab.adapter.mcp.request.GetTextRequest
import org.msc.librekollab.adapter.mcp.request.UpdateCommentRequest
import org.msc.librekollab.adapter.mcp.schema.ToolSchemaGenerator
import org.msc.librekollab.domain.KollabAPI
import org.msc.librekollab.domain.model.Comment
import org.msc.librekollab.domain.model.TextAnchor
import org.msc.librekollab.domain.model.change.Change
import org.msc.librekollab.domain.model.image.ImageMeta
import org.msc.librekollab.domain.model.image.ImageScaler
import org.msc.librekollab.domain.model.text.MarkedText
import java.util.Base64

class McpServer(
    private val kollab: KollabAPI,
    private val imageScaler: ImageScaler = ImageScaler(),
    private val schemaGenerator: ToolSchemaGenerator = ToolSchemaGenerator()
) {

    fun startSse(port: Int): EmbeddedServer<*, *> {
        return embeddedServer(Netty, port = port) {
            install(SSE)
            routing {
                mcp("/sse") {
                    buildServer().also { registerTools(it) }
                }
            }
        }.start(wait = false)
    }

    internal suspend fun connectTo(transport: Transport) {
        val server = buildServer()
        registerTools(server)
        val session = server.createSession(transport)
        val done = CompletableDeferred<Unit>()
        session.onClose { done.complete(Unit) }
        try {
            done.await()
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    private fun buildServer() = Server(
        serverInfo = Implementation(name = "librekollab", version = "1.0.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))
    )

    private inline fun <reified T> parseArgs(args: JsonObject): T = Json.decodeFromJsonElement(args)

    private fun registerTools(server: Server) {
        server.addTool(
            name = "list_documents",
            description = "List all document file names available in the workspace"
        ) { _ -> listDocuments() }

        server.addTool(
            name = "get_text",
            description = "Get the full text of a document. changeStatus: BEFORE (pre-edits), FUSION (includes tracked change markers, default), AFTER (edits applied)",
            inputSchema = schemaGenerator.schemaOf(GetTextRequest.serializer())
        ) { getText(parseArgs<GetTextRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "get_page_count",
            description = "Get the number of pages in a document",
            inputSchema = schemaGenerator.schemaOf(DocumentIdRequest.serializer())
        ) { getPageCount(parseArgs<DocumentIdRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "get_chapters",
            description = "List all chapter headings in a document, one per line",
            inputSchema = schemaGenerator.schemaOf(DocumentIdRequest.serializer())
        ) { getChapters(parseArgs<DocumentIdRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "get_text_by_pages",
            description = "Get the text from a page range (1-based). changeStatus: BEFORE/FUSION/AFTER",
            inputSchema = schemaGenerator.schemaOf(GetTextByPagesRequest.serializer())
        ) { getTextByPages(parseArgs<GetTextByPagesRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "get_text_by_chapter",
            description = "Get the text of a specific chapter. Use get_chapters first to find valid chapter names.",
            inputSchema = schemaGenerator.schemaOf(GetTextByChapterRequest.serializer())
        ) { getTextByChapter(parseArgs<GetTextByChapterRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "get_changes",
            description = "List all tracked changes (redlines) in a document as a JSON array",
            inputSchema = schemaGenerator.schemaOf(DocumentIdRequest.serializer())
        ) { getChanges(parseArgs<DocumentIdRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "get_edit_mode",
            description = "Check whether Track Changes is enabled for a document",
            inputSchema = schemaGenerator.schemaOf(DocumentIdRequest.serializer())
        ) { getEditMode(parseArgs<DocumentIdRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "edit_text",
            description = "Replace a text range in a document. Changes are tracked. Anchor only the exact range you want to replace — not the surrounding paragraph. anchorCharStart/anchorCharEnd are 0-based character offsets into the paragraph, where the paragraph's length itself denotes the position right after its last character (e.g. a 113-character paragraph ends at position 113, not 112). For a pure insertion (no deletion), set anchorCharStart == anchorCharEnd at the insertion point — use the paragraph's length to insert at its end — and provide only the new text as newText. The anchor range will be deleted and replaced by newText.",
            inputSchema = schemaGenerator.schemaOf(EditTextRequest.serializer())
        ) { editText(parseArgs<EditTextRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "get_comments",
            description = "List all comments in a document as a JSON array",
            inputSchema = schemaGenerator.schemaOf(DocumentIdRequest.serializer())
        ) { getComments(parseArgs<DocumentIdRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "get_comment",
            description = "Get a single comment by its ID as JSON",
            inputSchema = schemaGenerator.schemaOf(DocumentAndCommentIdRequest.serializer())
        ) { getComment(parseArgs<DocumentAndCommentIdRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "add_comment",
            description = "Add a comment anchored to a text range in a document",
            inputSchema = schemaGenerator.schemaOf(AddCommentRequest.serializer())
        ) { addComment(parseArgs<AddCommentRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "update_comment",
            description = "Update the text of an existing comment",
            inputSchema = schemaGenerator.schemaOf(UpdateCommentRequest.serializer())
        ) { updateComment(parseArgs<UpdateCommentRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "delete_comment",
            description = "Delete a comment from a document",
            inputSchema = schemaGenerator.schemaOf(DocumentAndCommentIdRequest.serializer())
        ) { deleteComment(parseArgs<DocumentAndCommentIdRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "get_image_metas",
            description = "List metadata for all images embedded in a document as a JSON array",
            inputSchema = schemaGenerator.schemaOf(DocumentIdRequest.serializer())
        ) { getImageMetas(parseArgs<DocumentIdRequest>(it.params.arguments as JsonObject)) }

        server.addTool(
            name = "get_image",
            description = "Get a single image by its ID as a PNG image. Optionally pass 'scale' " +
                "(0 exclusive to 1 inclusive) to downscale the PNG before it is returned, e.g. 0.25 " +
                "to shrink a too-large image to a quarter of its original width/height.",
            inputSchema = schemaGenerator.schemaOf(GetImageRequest.serializer())
        ) { getImage(parseArgs<GetImageRequest>(it.params.arguments as JsonObject)) }
    }

    private suspend fun listDocuments(): CallToolResult {
        val docs = kollab.listDocuments()
        return CallToolResult(content = listOf(TextContent(docs.joinToString("\n"))))
    }

    private suspend fun getText(request: GetTextRequest): CallToolResult {
        return CallToolResult(content = listOf(TextContent(
            Json.encodeToString(MarkedText.serializer(), kollab.getText(request.documentId, request.changeStatus))
        )))
    }

    private suspend fun getPageCount(request: DocumentIdRequest): CallToolResult {
        return CallToolResult(content = listOf(TextContent(kollab.getPageCount(request.documentId).toString())))
    }

    private suspend fun getChapters(request: DocumentIdRequest): CallToolResult {
        return CallToolResult(content = listOf(TextContent(kollab.getChapters(request.documentId).joinToString("\n"))))
    }

    private suspend fun getTextByPages(request: GetTextByPagesRequest): CallToolResult {
        return CallToolResult(content = listOf(TextContent(
            Json.encodeToString(
                MarkedText.serializer(),
                kollab.getTextByPages(request.documentId, request.fromPage, request.toPage, request.changeStatus)
            )
        )))
    }

    private suspend fun getTextByChapter(request: GetTextByChapterRequest): CallToolResult {
        return CallToolResult(content = listOf(TextContent(
            Json.encodeToString(
                MarkedText.serializer(),
                kollab.getTextByChapter(request.documentId, request.chapter, request.changeStatus)
            )
        )))
    }

    private suspend fun getChanges(request: DocumentIdRequest): CallToolResult {
        return CallToolResult(content = listOf(TextContent(
            Json.encodeToString(ListSerializer(Change.serializer()), kollab.getChanges(request.documentId))
        )))
    }

    private suspend fun getEditMode(request: DocumentIdRequest): CallToolResult {
        return CallToolResult(content = listOf(TextContent(kollab.getEditMode(request.documentId).toString())))
    }

    private suspend fun editText(request: EditTextRequest): CallToolResult {
        val anchor = TextAnchor(
            text = request.anchorText,
            paragraphIndex = request.anchorParagraphIndex,
            charStart = request.anchorCharStart,
            charEnd = request.anchorCharEnd
        )
        kollab.editText(request.documentId, anchor, MarkedText(request.newText, request.newTextProperties), request.author)
        return CallToolResult(content = listOf(TextContent("ok")))
    }

    private suspend fun getComments(request: DocumentIdRequest): CallToolResult {
        return CallToolResult(content = listOf(TextContent(
            Json.encodeToString(ListSerializer(Comment.serializer()), kollab.getComments(request.documentId))
        )))
    }

    private suspend fun getComment(request: DocumentAndCommentIdRequest): CallToolResult {
        val comment = kollab.getComment(request.documentId, request.commentId)
        val commentJson = comment?.let { Json.encodeToString(Comment.serializer(), it) } ?: "not found"
        return CallToolResult(content = listOf(TextContent(commentJson)))
    }

    private suspend fun addComment(request: AddCommentRequest): CallToolResult {
        val anchor = TextAnchor(
            text = request.anchorText,
            paragraphIndex = request.anchorParagraphIndex,
            charStart = request.anchorCharStart,
            charEnd = request.anchorCharEnd
        )
        kollab.addComment(
            documentId = request.documentId,
            commentText = request.commentText,
            author = request.author,
            anchor = anchor
        )
        return CallToolResult(content = listOf(TextContent("ok")))
    }

    private suspend fun updateComment(request: UpdateCommentRequest): CallToolResult {
        kollab.updateComment(request.documentId, request.commentId, request.newText)
        return CallToolResult(content = listOf(TextContent("ok")))
    }

    private suspend fun deleteComment(request: DocumentAndCommentIdRequest): CallToolResult {
        kollab.deleteComment(request.documentId, request.commentId)
        return CallToolResult(content = listOf(TextContent("ok")))
    }

    private suspend fun getImageMetas(request: DocumentIdRequest): CallToolResult {
        return CallToolResult(content = listOf(TextContent(
            Json.encodeToString(ListSerializer(ImageMeta.serializer()), kollab.getImageMetas(request.documentId))
        )))
    }

    private suspend fun getImage(request: GetImageRequest): CallToolResult {
        val bytes = prepareImage(request.documentId, request.imageId, request.scale)
            ?: return CallToolResult(content = listOf(TextContent("not found")))
        return CallToolResult(content = listOf(
            ImageContent(data = Base64.getEncoder().encodeToString(bytes), mimeType = "image/png")
        ))
    }

    private suspend fun prepareImage(documentId: String, imageId: String, scale: Double?): ByteArray? {
        val image = kollab.getImage(documentId, imageId) ?: return null
        return scale?.let { imageScaler.scale(image.bytes, it) } ?: image.bytes
    }
}
