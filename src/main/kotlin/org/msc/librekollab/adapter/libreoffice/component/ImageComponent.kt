package org.msc.librekollab.adapter.libreoffice.component

import com.sun.star.awt.Size
import com.sun.star.beans.PropertyValue
import com.sun.star.beans.XPropertySet
import com.sun.star.document.XExporter
import com.sun.star.document.XFilter
import com.sun.star.io.XInputStream
import com.sun.star.io.XOutputStream
import com.sun.star.lang.XComponent
import com.sun.star.lang.XMultiComponentFactory
import com.sun.star.text.XPageCursor
import com.sun.star.text.XTextContent
import com.sun.star.text.XTextDocument
import com.sun.star.text.XTextGraphicObjectsSupplier
import com.sun.star.text.XTextRange
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import org.msc.librekollab.adapter.libreoffice.ImageCache
import org.msc.librekollab.adapter.libreoffice.UnoClient
import org.msc.librekollab.domain.model.image.Image
import org.msc.librekollab.domain.model.image.ImageMeta
import java.io.ByteArrayOutputStream

class ImageComponent(
    private val componentContext: XComponentContext,
    private val unoClient: UnoClient,
    private val imageCache: ImageCache = ImageCache()
) {

    companion object {
        private const val SIZE_PROPERTY = "Size"
        private const val BASE64_INPUT_GROUP_BYTES = 3
        private const val BASE64_OUTPUT_GROUP_CHARS = 4
        private const val PIPE_SERVICE = "com.sun.star.io.Pipe"
        private const val PIPE_CHUNK_SIZE = 65536
        private const val GRAPHIC_EXPORT_FILTER_SERVICE = "com.sun.star.drawing.GraphicExportFilter"
        private const val OUTPUT_STREAM_PROPERTY = "OutputStream"
        private const val MEDIA_TYPE_PROPERTY = "MediaType"
        private const val PNG_MEDIA_TYPE = "image/png"
    }

    fun getCachedImage(imageId: String): Image? = imageCache.get(imageId)

    fun getImageMetas(documentId: String, textDoc: XTextDocument): List<ImageMeta> {
        val vc = unoClient.viewCursorOf(textDoc)
        val pc = UnoRuntime.queryInterface(XPageCursor::class.java, vc)
        return getGraphicShapes(textDoc).map { (shapeName, shape) ->
            val image = cachedImageOf(documentId, textDoc, shapeName, shape)
            vc.gotoRange(shapeAnchorRange(shape).start, false)
            val page = pc.page.toInt()
            val sizeMb = base64EncodedByteCount(image.bytes.size) / (1024.0 * 1024.0)
            ImageMeta(image.id, image.width, image.height, sizeMb, page, image.textAnchor)
        }
    }

    fun getImage(documentId: String, textDoc: XTextDocument, imageId: String): Image? =
        getGraphicShapes(textDoc).asSequence()
            .map { (shapeName, shape) -> cachedImageOf(documentId, textDoc, shapeName, shape) }
            .firstOrNull { it.id == imageId }

    private fun getGraphicShapes(textDoc: XTextDocument): List<Pair<String, Any>> {
        val supplier = UnoRuntime.queryInterface(XTextGraphicObjectsSupplier::class.java, textDoc)
            ?: return emptyList()
        val nameAccess = supplier.graphicObjects
        return nameAccess.elementNames.mapNotNull { name -> nameAccess.getByName(name)?.let { name to it } }
    }

    private fun shapeAnchorRange(shape: Any): XTextRange =
        UnoRuntime.queryInterface(XTextContent::class.java, shape).anchor

    private fun imageOf(textDoc: XTextDocument, shape: Any): Image {
        val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, shape)
        val size = propSet.getPropertyValue(SIZE_PROPERTY) as Size
        val anchor = unoClient.buildTextAnchor(textDoc, shapeAnchorRange(shape))
        val bytes = exportShapePng(shape)
        return Image(bytes, size.Width, size.Height, anchor)
    }

    private fun cachedImageOf(documentId: String, textDoc: XTextDocument, shapeName: String, shape: Any): Image {
        imageCache.getByShape(documentId, shapeName)?.let { return it }
        val image = imageOf(textDoc, shape)
        imageCache.put(documentId, shapeName, image)
        return image
    }

    private fun base64EncodedByteCount(rawByteCount: Int): Int {
        val inputGroups = (rawByteCount + BASE64_INPUT_GROUP_BYTES - 1) / BASE64_INPUT_GROUP_BYTES
        return inputGroups * BASE64_OUTPUT_GROUP_CHARS
    }

    private fun exportShapePng(shape: Any): ByteArray {
        val smgr = UnoRuntime.queryInterface(XMultiComponentFactory::class.java, componentContext.serviceManager)
        val pipeObj = smgr.createInstanceWithContext(PIPE_SERVICE, componentContext)
        val pipeIn = UnoRuntime.queryInterface(XInputStream::class.java, pipeObj)
        val pipeOut = UnoRuntime.queryInterface(XOutputStream::class.java, pipeObj)
        val exporter = UnoRuntime.queryInterface(
            XExporter::class.java,
            smgr.createInstanceWithContext(GRAPHIC_EXPORT_FILTER_SERVICE, componentContext)
        )
        exporter.setSourceDocument(
            UnoRuntime.queryInterface(XComponent::class.java, shape)
                ?: throw IllegalStateException("Graphic shape does not implement XComponent")
        )
        UnoRuntime.queryInterface(XFilter::class.java, exporter).filter(arrayOf(
            PropertyValue().apply { Name = OUTPUT_STREAM_PROPERTY; Value = pipeOut },
            PropertyValue().apply { Name = MEDIA_TYPE_PROPERTY; Value = PNG_MEDIA_TYPE }
        ))
        pipeOut.closeOutput()
        return readAllBytes(pipeIn)
    }

    private fun readAllBytes(input: XInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val holder = Array(1) { ByteArray(0) }
        while (true) {
            val n = input.readBytes(holder, PIPE_CHUNK_SIZE)
            if (n == 0) break
            out.write(holder[0])
        }
        input.closeInput()
        return out.toByteArray()
    }
}
