package org.msc.liberekollab.adapter.mcp

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
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.msc.liberekollab.domain.model.Comment
import org.msc.liberekollab.domain.model.TextAnchor
import org.msc.liberekollab.domain.model.change.Change
import org.msc.liberekollab.domain.model.change.ChangeStatus
import org.msc.liberekollab.domain.model.text.MarkedText
import org.msc.liberekollab.domain.model.text.properties.TextProperty
import org.msc.liberekollab.domain.KollabAPI

class McpServer(private val kollab: KollabAPI) {

    fun startSse(port: Int): EmbeddedServer<*, *> =
        embeddedServer(Netty, port = port) {
            install(SSE)
            routing {
                mcp("/sse") {
                    buildServer().also { registerTools(it) }
                }
            }
        }.start(wait = false)

    private fun buildServer() = Server(
        serverInfo = Implementation(name = "liberekollab", version = "1.0.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))
    )

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

    private fun registerTools(server: Server) {
        server.addTool(
            name = "list_documents",
            description = "List all document file names available in the workspace"
        ) { _ -> listDocuments() }

        server.addTool(
            name = "get_text",
            description = "Get the full text of a document. changeStatus: BEFORE (pre-edits), FUSION (includes tracked change markers, default), AFTER (edits applied)",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                    put("changeStatus", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("BEFORE"); add("FUSION"); add("AFTER") })
                    })
                },
                required = listOf("documentId")
            )
        ) { getText(it.params.arguments as JsonObject) }

        server.addTool(
            name = "get_page_count",
            description = "Get the number of pages in a document",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                },
                required = listOf("documentId")
            )
        ) { getPageCount(it.params.arguments as JsonObject) }

        server.addTool(
            name = "get_chapters",
            description = "List all chapter headings in a document, one per line",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                },
                required = listOf("documentId")
            )
        ) { getChapters(it.params.arguments as JsonObject) }

        server.addTool(
            name = "get_text_by_pages",
            description = "Get the text from a page range (1-based). changeStatus: BEFORE/FUSION/AFTER",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                    put("fromPage", buildJsonObject { put("type", "integer") })
                    put("toPage", buildJsonObject { put("type", "integer") })
                    put("changeStatus", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("BEFORE"); add("FUSION"); add("AFTER") })
                    })
                },
                required = listOf("documentId", "fromPage", "toPage")
            )
        ) { getTextByPages(it.params.arguments as JsonObject) }

        server.addTool(
            name = "get_text_by_chapter",
            description = "Get the text of a specific chapter. Use get_chapters first to find valid chapter names.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                    put("chapter", buildJsonObject { put("type", "string") })
                    put("changeStatus", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("BEFORE"); add("FUSION"); add("AFTER") })
                    })
                },
                required = listOf("documentId", "chapter")
            )
        ) { getTextByChapter(it.params.arguments as JsonObject) }

        server.addTool(
            name = "get_changes",
            description = "List all tracked changes (redlines) in a document as a JSON array",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                },
                required = listOf("documentId")
            )
        ) { getChanges(it.params.arguments as JsonObject) }

        server.addTool(
            name = "get_edit_mode",
            description = "Check whether Track Changes is enabled for a document",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                },
                required = listOf("documentId")
            )
        ) { getEditMode(it.params.arguments as JsonObject) }

        server.addTool(
            name = "edit_text",
            description = "Replace a text range in a document. Changes are tracked. Anchor only the exact range you want to replace — not the surrounding paragraph. For a pure insertion (no deletion), set anchorCharStart == anchorCharEnd at the insertion point and provide only the new text as newText. The anchor range will be deleted and replaced by newText.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                    put("anchorText", buildJsonObject { put("type", "string") })
                    put("anchorParagraphIndex", buildJsonObject { put("type", "integer") })
                    put("anchorCharStart", buildJsonObject { put("type", "integer") })
                    put("anchorCharEnd", buildJsonObject { put("type", "integer") })
                    put("newText", buildJsonObject { put("type", "string") })
                    put("newTextProperties", buildJsonObject {
                        put("type", "array")
                        put("description", "optional formatting for newText: [{\"type\":\"bold|italic|underline|strikethrough\",\"markIndex\":{\"paragraphIndex\":0,\"from\":0,\"to\":N}}]. paragraphIndex is 0-based relative to newText, from/to are character offsets within that paragraph.")
                    })
                    put("author", buildJsonObject { put("type", "string"); put("description", "author name attached to the tracked change") })
                },
                required = listOf("documentId", "anchorText", "anchorParagraphIndex", "anchorCharStart", "anchorCharEnd", "newText")
            )
        ) { editText(it.params.arguments as JsonObject) }

        server.addTool(
            name = "get_comments",
            description = "List all comments in a document as a JSON array",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                },
                required = listOf("documentId")
            )
        ) { getComments(it.params.arguments as JsonObject) }

        server.addTool(
            name = "get_comment",
            description = "Get a single comment by its ID as JSON",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                    put("commentId", buildJsonObject { put("type", "string") })
                },
                required = listOf("documentId", "commentId")
            )
        ) { getComment(it.params.arguments as JsonObject) }

        server.addTool(
            name = "add_comment",
            description = "Add a comment anchored to a text range in a document",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                    put("commentText", buildJsonObject { put("type", "string") })
                    put("author", buildJsonObject { put("type", "string") })
                    put("anchorText", buildJsonObject { put("type", "string") })
                    put("anchorParagraphIndex", buildJsonObject { put("type", "integer") })
                    put("anchorCharStart", buildJsonObject { put("type", "integer") })
                    put("anchorCharEnd", buildJsonObject { put("type", "integer") })
                },
                required = listOf("documentId", "commentText", "author", "anchorText", "anchorParagraphIndex", "anchorCharStart", "anchorCharEnd")
            )
        ) { addComment(it.params.arguments as JsonObject) }

        server.addTool(
            name = "update_comment",
            description = "Update the text of an existing comment",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                    put("commentId", buildJsonObject { put("type", "string") })
                    put("newText", buildJsonObject { put("type", "string") })
                },
                required = listOf("documentId", "commentId", "newText")
            )
        ) { updateComment(it.params.arguments as JsonObject) }

        server.addTool(
            name = "delete_comment",
            description = "Delete a comment from a document",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("documentId", buildJsonObject { put("type", "string") })
                    put("commentId", buildJsonObject { put("type", "string") })
                },
                required = listOf("documentId", "commentId")
            )
        ) { deleteComment(it.params.arguments as JsonObject) }
    }

    private suspend fun listDocuments(): CallToolResult {
        val docs = kollab.listDocuments()
        return CallToolResult(content = listOf(TextContent(docs.joinToString("\n"))))
    }

    private suspend fun getText(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        val changeStatus = args["changeStatus"]?.jsonPrimitive?.content
            ?.let { ChangeStatus.valueOf(it) } ?: ChangeStatus.FUSION
        return CallToolResult(content = listOf(TextContent(Json.encodeToString(MarkedText.serializer(), kollab.getText(documentId, changeStatus)))))
    }

    private suspend fun getPageCount(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        return CallToolResult(content = listOf(TextContent(kollab.getPageCount(documentId).toString())))
    }

    private suspend fun getChapters(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        return CallToolResult(content = listOf(TextContent(kollab.getChapters(documentId).joinToString("\n"))))
    }

    private suspend fun getTextByPages(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        val fromPage = args["fromPage"]!!.jsonPrimitive.int
        val toPage = args["toPage"]!!.jsonPrimitive.int
        val changeStatus = args["changeStatus"]?.jsonPrimitive?.content
            ?.let { ChangeStatus.valueOf(it) } ?: ChangeStatus.FUSION
        return CallToolResult(content = listOf(TextContent(Json.encodeToString(MarkedText.serializer(), kollab.getTextByPages(documentId, fromPage, toPage, changeStatus)))))
    }

    private suspend fun getTextByChapter(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        val chapter = args["chapter"]!!.jsonPrimitive.content
        val changeStatus = args["changeStatus"]?.jsonPrimitive?.content
            ?.let { ChangeStatus.valueOf(it) } ?: ChangeStatus.FUSION
        return CallToolResult(content = listOf(TextContent(Json.encodeToString(MarkedText.serializer(), kollab.getTextByChapter(documentId, chapter, changeStatus)))))
    }

    private suspend fun getChanges(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        return CallToolResult(content = listOf(TextContent(
            Json.encodeToString(ListSerializer(Change.serializer()), kollab.getChanges(documentId))
        )))
    }

    private suspend fun getEditMode(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        return CallToolResult(content = listOf(TextContent(kollab.getEditMode(documentId).toString())))
    }

    private suspend fun editText(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        val anchor = TextAnchor(
            text = args["anchorText"]!!.jsonPrimitive.content,
            paragraphIndex = args["anchorParagraphIndex"]!!.jsonPrimitive.int,
            charStart = args["anchorCharStart"]!!.jsonPrimitive.int,
            charEnd = args["anchorCharEnd"]!!.jsonPrimitive.int
        )
        val properties: List<TextProperty> = args["newTextProperties"]
            ?.let { Json.decodeFromString(ListSerializer(TextProperty.serializer()), it.toString()) }
            ?: emptyList()
        val author = args["author"]?.jsonPrimitive?.content ?: ""
        kollab.editText(documentId, anchor, MarkedText(args["newText"]!!.jsonPrimitive.content, properties), author)
        return CallToolResult(content = listOf(TextContent("ok")))
    }

    private suspend fun getComments(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        return CallToolResult(content = listOf(TextContent(
            Json.encodeToString(ListSerializer(Comment.serializer()), kollab.getComments(documentId))
        )))
    }

    private suspend fun getComment(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        val commentId = args["commentId"]!!.jsonPrimitive.content
        val comment = kollab.getComment(documentId, commentId)
        return CallToolResult(content = listOf(TextContent(
            if (comment != null) Json.encodeToString(Comment.serializer(), comment) else "not found"
        )))
    }

    private suspend fun addComment(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        val anchor = TextAnchor(
            text = args["anchorText"]!!.jsonPrimitive.content,
            paragraphIndex = args["anchorParagraphIndex"]!!.jsonPrimitive.int,
            charStart = args["anchorCharStart"]!!.jsonPrimitive.int,
            charEnd = args["anchorCharEnd"]!!.jsonPrimitive.int
        )
        kollab.addComment(
            documentId = documentId,
            commentText = args["commentText"]!!.jsonPrimitive.content,
            author = args["author"]!!.jsonPrimitive.content,
            anchor = anchor
        )
        return CallToolResult(content = listOf(TextContent("ok")))
    }

    private suspend fun updateComment(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        val commentId = args["commentId"]!!.jsonPrimitive.content
        kollab.updateComment(documentId, commentId, args["newText"]!!.jsonPrimitive.content)
        return CallToolResult(content = listOf(TextContent("ok")))
    }

    private suspend fun deleteComment(args: JsonObject): CallToolResult {
        val documentId = args["documentId"]!!.jsonPrimitive.content
        val commentId = args["commentId"]!!.jsonPrimitive.content
        kollab.deleteComment(documentId, commentId)
        return CallToolResult(content = listOf(TextContent("ok")))
    }
}
