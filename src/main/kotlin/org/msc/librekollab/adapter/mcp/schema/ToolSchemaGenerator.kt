package org.msc.librekollab.adapter.mcp.schema

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ToolSchemaGenerator {

    fun schemaOf(serializer: KSerializer<*>): ToolSchema {
        val descriptor = serializer.descriptor
        val indices = 0 until descriptor.elementsCount
        val properties = buildJsonObject {
            indices.forEach { put(descriptor.getElementName(it), propertySchema(descriptor, it)) }
        }
        val required = indices.filterNot { descriptor.isElementOptional(it) }.map { descriptor.getElementName(it) }
        return ToolSchema(properties = properties, required = required)
    }

    private fun propertySchema(descriptor: SerialDescriptor, index: Int): JsonObject = buildJsonObject {
        typeSchema(descriptor.getElementDescriptor(index)).forEach { (key, value) -> put(key, value) }
        descriptionOf(descriptor, index)?.let { put("description", it) }
    }

    private fun typeSchema(descriptor: SerialDescriptor): JsonObject {
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> buildJsonObject { put("type", "string") }
            PrimitiveKind.INT -> buildJsonObject { put("type", "integer") }
            PrimitiveKind.DOUBLE -> buildJsonObject { put("type", "number") }
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { (0 until descriptor.elementsCount).forEach { add(descriptor.getElementName(it)) } })
            }
            StructureKind.LIST -> buildJsonObject { put("type", "array") }
            else -> error("Unsupported schema kind for MCP tool property: ${descriptor.kind}")
        }
    }

    private fun descriptionOf(descriptor: SerialDescriptor, index: Int): String? =
        descriptor.getElementAnnotations(index).filterIsInstance<Description>().firstOrNull()?.text
}
