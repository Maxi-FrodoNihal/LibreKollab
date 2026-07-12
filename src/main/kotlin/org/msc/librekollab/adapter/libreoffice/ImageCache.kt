package org.msc.librekollab.adapter.libreoffice

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.msc.librekollab.domain.model.image.Image
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class ImageCache(
    maxWeightBytes: Long = DEFAULT_MAX_WEIGHT_BYTES,
    expireAfterAccessMinutes: Long = DEFAULT_EXPIRE_AFTER_ACCESS_MINUTES,
    maxShapeIndexSize: Long = DEFAULT_MAX_SHAPE_INDEX_SIZE
) {
    // Single source of truth for actual image bytes, weight-bounded so total retained PNG data
    // stays capped regardless of how many images/documents were touched.
    private val byId: Cache<String, Image> = Caffeine.newBuilder()
        .maximumWeight(maxWeightBytes)
        .weigher { _: String, image: Image -> image.bytes.size }
        .expireAfterAccess(expireAfterAccessMinutes, TimeUnit.MINUTES)
        .build()

    // Index only, no bytes: maps a shape known before export to the id under which its Image
    // lives in `byId`. Bounded by entry count, not weight, since entries are just two short strings.
    private val shapeToId: Cache<Pair<String, String>, String> = Caffeine.newBuilder()
        .maximumSize(maxShapeIndexSize)
        .expireAfterAccess(expireAfterAccessMinutes, TimeUnit.MINUTES)
        .build()

    private val log = LoggerFactory.getLogger(ImageCache::class.java)

    companion object {
        private const val DEFAULT_MAX_WEIGHT_BYTES = 200L * 1024 * 1024
        private const val DEFAULT_EXPIRE_AFTER_ACCESS_MINUTES = 30L
        private const val DEFAULT_MAX_SHAPE_INDEX_SIZE = 1000L
    }

    fun get(imageId: String): Image? {
        val image = byId.getIfPresent(imageId)
        if (image != null) {
            log.info("Image cache hit by id: {}", imageId)
        }
        return image
    }

    // documentId + the UNO shape's Name property, known before the shape is ever exported —
    // unlike imageId (a hash of the exported bytes), this key lets us skip the export entirely
    // for a shape we've already seen, instead of only benefiting a lookup by an already-known id.
    // A miss here can mean either "never seen" or "its Image aged out of byId" — both correctly
    // fall through to a fresh export in the caller.
    fun getByShape(documentId: String, shapeName: String): Image? {
        val imageId = shapeToId.getIfPresent(documentId to shapeName) ?: return null
        val image = byId.getIfPresent(imageId)
        if (image != null) {
            log.info("Image cache hit by shape: documentId={}, shapeName={}", documentId, shapeName)
        }
        return image
    }

    fun put(documentId: String, shapeName: String, image: Image) {
        byId.put(image.id, image)
        shapeToId.put(documentId to shapeName, image.id)
    }
}
