package org.msc.liberekollab.domain.model.text

import kotlinx.serialization.Serializable

@Serializable
data class MarkIndex(val paragraphIndex: Int, val from: Int, val to: Int)
