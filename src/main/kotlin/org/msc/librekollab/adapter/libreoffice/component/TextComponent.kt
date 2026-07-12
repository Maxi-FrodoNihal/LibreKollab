package org.msc.librekollab.adapter.libreoffice.component

import com.sun.star.awt.FontSlant
import com.sun.star.beans.XPropertySet
import com.sun.star.container.XEnumerationAccess
import com.sun.star.text.ControlCharacter
import com.sun.star.text.XPageCursor
import com.sun.star.text.XTextCursor
import com.sun.star.text.XTextDocument
import com.sun.star.text.XTextRange
import com.sun.star.uno.Exception as UnoException
import com.sun.star.uno.UnoRuntime
import org.msc.librekollab.adapter.libreoffice.UnoClient
import org.msc.librekollab.domain.model.anchor.TextAnchor
import org.msc.librekollab.domain.model.change.ChangeStatus
import org.msc.librekollab.domain.model.text.MarkIndex
import org.msc.librekollab.domain.model.text.MarkedText
import org.msc.librekollab.domain.model.text.properties.BoldProperty
import org.msc.librekollab.domain.model.text.properties.ItalicProperty
import org.msc.librekollab.domain.model.text.properties.StrikethroughProperty
import org.msc.librekollab.domain.model.text.properties.TextProperty
import org.msc.librekollab.domain.model.text.properties.UnderlineProperty
import kotlin.reflect.KClass

class TextComponent(private val unoClient: UnoClient) {

    private data class MarkedTextScan(
        val offset: Int = 0,
        val inDelete: Boolean = false,
        val inInsert: Boolean = false,
        val text: String = "",
        val properties: List<TextProperty> = emptyList()
    )

    private data class FormattingRun(val text: String, val properties: Set<KClass<out TextProperty>>)

    companion object {
        private const val OUTLINE_LEVEL_PROPERTY = "OutlineLevel"

        private const val TEXT_PORTION_TYPE_PROPERTY = "TextPortionType"
        private const val PORTION_TYPE_REDLINE = "Redline"
        private const val PORTION_TYPE_TEXT = "Text"

        private const val IS_START_PROPERTY = "IsStart"
        private const val REDLINE_TYPE_PROPERTY = "RedlineType"
        private const val REDLINE_TYPE_INSERT = "Insert"
        private const val REDLINE_TYPE_DELETE = "Delete"

        private const val CHAR_WEIGHT_PROPERTY = "CharWeight"
        private const val CHAR_POSTURE_PROPERTY = "CharPosture"
        private const val CHAR_UNDERLINE_PROPERTY = "CharUnderline"
        private const val CHAR_STRIKEOUT_PROPERTY = "CharStrikeout"
        private const val BOLD_CHAR_WEIGHT = 150f
        private const val NORMAL_CHAR_WEIGHT = 100f
    }

    fun getText(textDoc: XTextDocument, changeStatus: ChangeStatus): MarkedText {
        val results = unoClient.paragraphsOf(textDoc)
            .mapIndexed { idx, para -> extractParagraphMarkedText(para, changeStatus, idx) }
            .toList()
        return MarkedText(results.joinToString("\n") { it.first }, results.flatMap { it.second })
    }

    fun getTextByPages(textDoc: XTextDocument, fromPage: Int, toPage: Int, changeStatus: ChangeStatus): MarkedText {
        val vc = unoClient.viewCursorOf(textDoc)
        val pc = UnoRuntime.queryInterface(XPageCursor::class.java, vc)
        val results = unoClient.paragraphsOf(textDoc)
            .mapIndexed { idx, element ->
                vc.gotoRange(UnoRuntime.queryInterface(XTextRange::class.java, element).start, false)
                Triple(idx, element, pc.page.toInt())
            }
            .takeWhile { (_, _, pageNum) -> pageNum <= toPage }
            .filter { (_, _, pageNum) -> pageNum in fromPage..toPage }
            .map { (idx, element, _) -> extractParagraphMarkedText(element, changeStatus, idx) }
            .toList()
        return MarkedText(results.joinToString("\n") { it.first }, results.flatMap { it.second })
    }

    // Deliberately imperative: the stop level is only known once the matching heading is found mid-scan,
    // and a fold/takeWhile rewrite would need to materialize every paragraph upfront, losing the early
    // exit once the chapter ends.
    fun getTextByChapter(textDoc: XTextDocument, chapter: String, changeStatus: ChangeStatus): MarkedText {
        val sb = StringBuilder()
        val properties = mutableListOf<TextProperty>()
        var inChapter = false
        var chapterLevel = 0
        var paraIdx = 0
        val paragraphs = unoClient.paragraphEnumerationOf(textDoc)
        while (paragraphs.hasMoreElements()) {
            val element = paragraphs.nextElement()
            val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, element)
            val textRange = UnoRuntime.queryInterface(XTextRange::class.java, element)
            val paraOutlineLevel = outlineLevel(propSet)
            val paraText = textRange.string
            if (paraOutlineLevel > 0) {
                if (inChapter && paraOutlineLevel <= chapterLevel) {
                    break
                }
                if (paraText.trim() == chapter.trim()) {
                    inChapter = true
                    chapterLevel = paraOutlineLevel
                }
            } else if (inChapter && paraText.isNotBlank()) {
                val (text, props) = extractParagraphMarkedText(element, changeStatus, paraIdx)
                sb.append(text).append("\n")
                properties.addAll(props)
            }
            paraIdx++
        }
        return MarkedText(sb.toString().trimEnd(), properties)
    }

    fun getChapters(textDoc: XTextDocument): List<String> =
        unoClient.paragraphsOf(textDoc)
            .filter { outlineLevel(UnoRuntime.queryInterface(XPropertySet::class.java, it)) > 0 }
            .map { UnoRuntime.queryInterface(XTextRange::class.java, it).string }
            .toList()

    fun editText(textDoc: XTextDocument, anchor: TextAnchor, newText: MarkedText) {
        unoClient.resolveAnchorRange(textDoc, anchor).setString("")
        insertFormattedText(textDoc, anchor, newText)
    }

    fun extractParagraphMarkedText(para: Any, changeStatus: ChangeStatus, paragraphIndex: Int): Pair<String, List<TextProperty>> {
        val portions = UnoRuntime.queryInterface(XEnumerationAccess::class.java, para)?.createEnumeration()
            ?: return Pair("", emptyList())
        val result = unoClient.enumerationSequence(portions)
            .fold(MarkedTextScan()) { state, portion -> scanMarkedTextPortion(state, portion, changeStatus, paragraphIndex) }
        return Pair(result.text, result.properties)
    }

    private fun outlineLevel(props: XPropertySet): Int {
        return when (val v = props.getPropertyValue(OUTLINE_LEVEL_PROPERTY)) {
            is Int -> v
            is Short -> v.toInt()
            else -> 0
        }
    }

    private fun scanMarkedTextPortion(state: MarkedTextScan, portion: Any, changeStatus: ChangeStatus, paragraphIndex: Int): MarkedTextScan {
        val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, portion) ?: return state
        return when (propSet.getPropertyValue(TEXT_PORTION_TYPE_PROPERTY) as? String) {
            PORTION_TYPE_REDLINE -> scanRedlineFlag(state, propSet)
            PORTION_TYPE_TEXT -> scanMarkedText(state, propSet, portion, changeStatus, paragraphIndex)
            else -> state
        }
    }

    private fun scanRedlineFlag(state: MarkedTextScan, propSet: XPropertySet): MarkedTextScan {
        val isStart = propSet.getPropertyValue(IS_START_PROPERTY) as? Boolean ?: false
        return when (propSet.getPropertyValue(REDLINE_TYPE_PROPERTY) as? String) {
            REDLINE_TYPE_DELETE -> state.copy(inDelete = isStart)
            REDLINE_TYPE_INSERT -> state.copy(inInsert = isStart)
            else -> state
        }
    }

    private fun scanMarkedText(
        state: MarkedTextScan,
        propSet: XPropertySet,
        portion: Any,
        changeStatus: ChangeStatus,
        paragraphIndex: Int
    ): MarkedTextScan {
        val include = when (changeStatus) {
            ChangeStatus.BEFORE -> !state.inInsert
            ChangeStatus.AFTER -> !state.inDelete
            ChangeStatus.FUSION -> true
        }
        if (!include) {
            return state
        }
        val text = UnoRuntime.queryInterface(XTextRange::class.java, portion)?.string ?: ""
        if (text.isEmpty()) {
            return state
        }
        val idx = MarkIndex(paragraphIndex, state.offset, state.offset + text.length)
        val properties = state.properties + formattingProperties(propSet, idx)
        return state.copy(offset = state.offset + text.length, text = state.text + text, properties = properties)
    }

    private fun formattingProperties(propSet: XPropertySet, idx: MarkIndex): List<TextProperty> {
        val charWeight = try { propSet.getPropertyValue(CHAR_WEIGHT_PROPERTY) as? Float } catch (e: UnoException) { null }
        val charPosture = try { propSet.getPropertyValue(CHAR_POSTURE_PROPERTY) as? FontSlant } catch (e: UnoException) { null }
        val charUnderline = try { (propSet.getPropertyValue(CHAR_UNDERLINE_PROPERTY) as? Number)?.toInt() } catch (e: UnoException) { null }
        val charStrikeout = try { (propSet.getPropertyValue(CHAR_STRIKEOUT_PROPERTY) as? Number)?.toInt() } catch (e: UnoException) { null }
        return listOfNotNull(
            BoldProperty(idx).takeIf { charWeight != null && charWeight >= BOLD_CHAR_WEIGHT },
            ItalicProperty(idx).takeIf { charPosture != null && charPosture != FontSlant.NONE && charPosture != FontSlant.DONTKNOW },
            UnderlineProperty(idx).takeIf { charUnderline != null && charUnderline != 0 },
            StrikethroughProperty(idx).takeIf { charStrikeout != null && charStrikeout != 0 }
        )
    }

    // Inserts newText.text one formatting run at a time, setting the cursor's character properties
    // *before* each insertString call. This avoids ever reformatting already-inserted text: LibreOffice
    // tracks such a later attribute change as its own "Format" redline, which splits what should be one
    // logical Insert into two — losing the second half when getChanges() reads the portions back.
    private fun insertFormattedText(textDoc: XTextDocument, anchor: TextAnchor, markedText: MarkedText) {
        val anchorPara = unoClient.paragraphAt(textDoc, anchor.paragraphIndex)
        val cursor = textDoc.text.createTextCursorByRange(UnoRuntime.queryInterface(XTextRange::class.java, anchorPara).start)
        cursor.goRight(anchor.charEnd.toShort(), false)

        markedText.text.split('\n').forEachIndexed { lineIdx, line ->
            if (lineIdx > 0) {
                textDoc.text.insertControlCharacter(cursor, ControlCharacter.PARAGRAPH_BREAK, false)
                cursor.collapseToEnd()
            }
            formattingRuns(line, lineIdx, markedText.properties).forEach { run ->
                applyRunProperties(cursor, run.properties)
                textDoc.text.insertString(cursor, run.text, false)
                cursor.collapseToEnd()
            }
        }
    }

    private fun applyRunProperties(cursor: XTextCursor, properties: Set<KClass<out TextProperty>>) {
        val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, cursor)
        propSet.setPropertyValue(CHAR_WEIGHT_PROPERTY, if (BoldProperty::class in properties) BOLD_CHAR_WEIGHT else NORMAL_CHAR_WEIGHT)
        propSet.setPropertyValue(CHAR_POSTURE_PROPERTY, if (ItalicProperty::class in properties) FontSlant.ITALIC else FontSlant.NONE)
        propSet.setPropertyValue(CHAR_UNDERLINE_PROPERTY, (if (UnderlineProperty::class in properties) 1 else 0).toShort())
        propSet.setPropertyValue(CHAR_STRIKEOUT_PROPERTY, (if (StrikethroughProperty::class in properties) 1 else 0).toShort())
    }

    private fun formattingRuns(line: String, lineIdx: Int, properties: List<TextProperty>): List<FormattingRun> {
        if (line.isEmpty()) {
            return emptyList()
        }
        val lineProperties = properties.filter { it.markIndex.paragraphIndex == lineIdx }
        val cuts = (lineProperties.flatMap { listOf(it.markIndex.from, it.markIndex.to) } + listOf(0, line.length))
            .toSortedSet().toList()
        return cuts.zipWithNext { start, end ->
            FormattingRun(
                text = line.substring(start, end),
                properties = lineProperties.filter { start >= it.markIndex.from && end <= it.markIndex.to }.map { it::class }.toSet()
            )
        }.filter { it.text.isNotEmpty() }
    }
}
