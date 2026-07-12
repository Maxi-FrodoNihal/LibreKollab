package org.msc.librekollab.domain.model.image

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageScalerTest {

    private val scaler = ImageScaler()

    @Test
    fun `T01 scale halves width and height for factor 0-5`() {
        val original = pngOf(width = 100, height = 60)

        val scaled = scaler.scale(original, 0.5)

        val image = ImageIO.read(scaled.inputStream())
        assertEquals(50, image.width)
        assertEquals(30, image.height)
    }

    @Test
    fun `T02 scale with factor 1 returns the original size`() {
        val original = pngOf(width = 40, height = 40)

        val scaled = scaler.scale(original, 1.0)

        val image = ImageIO.read(scaled.inputStream())
        assertEquals(40, image.width)
        assertEquals(40, image.height)
    }

    @Test
    fun `T03 scale rejects a factor of 0`() {
        val original = pngOf(width = 10, height = 10)

        assertFailsWith<IllegalArgumentException> { scaler.scale(original, 0.0) }
    }

    @Test
    fun `T04 scale rejects a negative factor`() {
        val original = pngOf(width = 10, height = 10)

        assertFailsWith<IllegalArgumentException> { scaler.scale(original, -0.5) }
    }

    @Test
    fun `T05 scale rejects a factor greater than 1`() {
        val original = pngOf(width = 10, height = 10)

        assertFailsWith<IllegalArgumentException> { scaler.scale(original, 1.5) }
    }

    @Test
    fun `T06 scale rejects a corrupt image`() {
        val corrupt = byteArrayOf(1, 2, 3, 4, 5)

        assertFailsWith<IllegalArgumentException> { scaler.scale(corrupt, 0.5) }
    }

    @Test
    fun `T07 scale coerces a tiny target size up to at least 1 pixel`() {
        val original = pngOf(width = 10, height = 10)

        val scaled = scaler.scale(original, 0.01)

        val image = ImageIO.read(scaled.inputStream())
        assertTrue(image.width >= 1)
        assertTrue(image.height >= 1)
    }

    private fun pngOf(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
