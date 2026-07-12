package org.msc.librekollab.adapter.libreoffice

import com.sun.star.container.XEnumeration
import com.sun.star.container.XEnumerationAccess
import com.sun.star.frame.XModel
import com.sun.star.text.XPageCursor
import com.sun.star.text.XTextDocument
import com.sun.star.text.XTextRange
import com.sun.star.text.XTextRangeCompare
import com.sun.star.text.XTextViewCursor
import com.sun.star.text.XTextViewCursorSupplier
import com.sun.star.uno.UnoRuntime
import com.sun.star.util.DateTime
import kotlinx.datetime.LocalDateTime
import org.msc.librekollab.domain.model.anchor.TextAnchor

class UnoClient {

    fun enumerationSequence(enumeration: XEnumeration): Sequence<Any> =
        generateSequence { if (enumeration.hasMoreElements()) enumeration.nextElement() else null }

    fun viewCursorOf(textDoc: XTextDocument): XTextViewCursor {
        val model = UnoRuntime.queryInterface(XModel::class.java, textDoc)
        val vcSupplier = UnoRuntime.queryInterface(XTextViewCursorSupplier::class.java, model.currentController)
        return vcSupplier.viewCursor
    }

    fun pageCursorOf(textDoc: XTextDocument): XPageCursor =
        UnoRuntime.queryInterface(XPageCursor::class.java, viewCursorOf(textDoc))

    fun paragraphAt(textDoc: XTextDocument, index: Int): Any {
        val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text).createEnumeration()
        return enumerationSequence(paragraphs).elementAtOrNull(index)
            ?: throw IllegalArgumentException("Paragraph index $index not found in document")
    }

    fun resolveAnchorRange(textDoc: XTextDocument, anchor: TextAnchor): XTextRange {
        val para = paragraphAt(textDoc, anchor.paragraphIndex)
        val paraStart = UnoRuntime.queryInterface(XTextRange::class.java, para).start
        val cursor = textDoc.text.createTextCursorByRange(paraStart)
        cursor.goRight(anchor.charStart.toShort(), false)
        cursor.goRight((anchor.charEnd - anchor.charStart).toShort(), true)
        return cursor
    }

    fun buildTextAnchor(textDoc: XTextDocument, anchorRange: XTextRange): TextAnchor {
        val compare = UnoRuntime.queryInterface(XTextRangeCompare::class.java, textDoc.text)
        val paraEnum = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text).createEnumeration()
        val paragraphList = enumerationSequence(paraEnum)
            .map { UnoRuntime.queryInterface(XTextRange::class.java, it) }
            .toList()

        val paragraphIndex = paragraphList.indexOfLast { para ->
            compare.compareRegionStarts(anchorRange.start, para.start) <= 0
        }.takeIf { it >= 0 } ?: 0

        val paraStartCursor = textDoc.text.createTextCursorByRange(paragraphList[paragraphIndex].start)
        paraStartCursor.gotoStart(true)
        val anchorStartCursor = textDoc.text.createTextCursorByRange(anchorRange.start)
        anchorStartCursor.gotoStart(true)
        val charStart = anchorStartCursor.string.length - paraStartCursor.string.length
        return TextAnchor(anchorRange.string, paragraphIndex, charStart, charStart + anchorRange.string.length)
    }

    fun localDateTimeOf(dt: DateTime): LocalDateTime = LocalDateTime(
        dt.Year.toInt(), dt.Month.toInt(), dt.Day.toInt(),
        dt.Hours.toInt(), dt.Minutes.toInt(), dt.Seconds.toInt()
    )

    fun unoDateTimeNow(): DateTime {
        val now = java.time.LocalDateTime.now()
        return DateTime().apply {
            Year = now.year.toShort()
            Month = now.monthValue.toShort()
            Day = now.dayOfMonth.toShort()
            Hours = now.hour.toShort()
            Minutes = now.minute.toShort()
            Seconds = now.second.toShort()
        }
    }
}
