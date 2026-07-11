package org.msc.librekollab.adapter.mcp.request

import kotlinx.serialization.Serializable
import org.msc.librekollab.adapter.mcp.schema.Description
import org.msc.librekollab.domain.KollabAPI
import org.msc.librekollab.domain.model.text.properties.TextProperty

@Serializable
data class EditTextRequest(
    val documentId: String,
    val anchorText: String,
    val anchorParagraphIndex: Int,
    val anchorCharStart: Int,
    val anchorCharEnd: Int,
    val newText: String,
    @Description(
        "optional formatting for newText: [{\"type\":\"bold|italic|underline|strikethrough\"," +
            "\"markIndex\":{\"paragraphIndex\":0,\"from\":0,\"to\":N}}]. paragraphIndex is 0-based " +
            "relative to newText, from/to are character offsets within that paragraph."
    )
    val newTextProperties: List<TextProperty> = emptyList(),
    @Description("author name attached to the tracked change")
    val author: String = KollabAPI.UNKNOWN_AUTHOR
)
