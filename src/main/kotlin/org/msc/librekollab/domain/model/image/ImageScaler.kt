package org.msc.librekollab.domain.model.image

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageScaler {

    companion object {
        private const val PNG_FORMAT = "png"
    }

    fun scale(image: ByteArray, factor: Double): ByteArray {
        require(factor > 0.0 && factor <= 1.0) { "scale must be greater than 0 and at most 1" }
        val original = ImageIO.read(ByteArrayInputStream(image))
        val targetWidth = (original.width * factor).toInt().coerceAtLeast(1)
        val targetHeight = (original.height * factor).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(original, 0, 0, targetWidth, targetHeight, null)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(scaled, PNG_FORMAT, out)
        return out.toByteArray()
    }
}
