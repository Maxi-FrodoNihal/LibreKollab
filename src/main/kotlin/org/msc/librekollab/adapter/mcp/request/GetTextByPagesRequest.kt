package org.msc.librekollab.adapter.mcp.request

import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.change.ChangeStatus

@Serializable
data class GetTextByPagesRequest(
    val documentId: String,
    val fromPage: Int,
    val toPage: Int,
    val changeStatus: ChangeStatus = ChangeStatus.FUSION
)
