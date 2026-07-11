package org.msc.librekollab.adapter.mcp.request

import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.change.ChangeStatus

@Serializable
data class GetTextByChapterRequest(
    val documentId: String,
    val chapter: String,
    val changeStatus: ChangeStatus = ChangeStatus.FUSION
)
