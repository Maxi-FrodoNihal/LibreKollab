package org.msc.librekollab.adapter.mcp.request

import kotlinx.serialization.Serializable
import org.msc.librekollab.adapter.mcp.schema.Description

@Serializable
data class SearchRequest(
    val documentId: String,
    val searchText: String,
    @Description("1-based result page number, default 1")
    val page: Int = 1,
    @Description("number of findings per page, default 10")
    val size: Int = 10
)
