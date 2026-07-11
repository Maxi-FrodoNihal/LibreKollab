package org.msc.librekollab.adapter.libreoffice

import com.sun.star.awt.FontSlant
import com.sun.star.awt.Size
import com.sun.star.beans.PropertyValue
import com.sun.star.beans.XPropertySet
import com.sun.star.container.XEnumeration
import com.sun.star.container.XEnumerationAccess
import com.sun.star.document.XExporter
import com.sun.star.document.XFilter
import com.sun.star.frame.XModel
import com.sun.star.io.XInputStream
import com.sun.star.io.XOutputStream
import com.sun.star.lang.XComponent
import com.sun.star.lang.XMultiComponentFactory
import com.sun.star.lang.XMultiServiceFactory
import com.sun.star.lang.XServiceInfo
import com.sun.star.text.ControlCharacter
import com.sun.star.text.XPageCursor
import com.sun.star.text.XTextContent
import com.sun.star.text.XTextGraphicObjectsSupplier
import com.sun.star.text.XTextCursor
import com.sun.star.text.XTextDocument
import com.sun.star.text.XTextFieldsSupplier
import com.sun.star.text.XTextRange
import com.sun.star.text.XTextRangeCompare
import com.sun.star.text.XTextViewCursor
import com.sun.star.text.XTextViewCursorSupplier
import com.sun.star.uno.Exception as UnoException
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import com.sun.star.util.DateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import org.msc.librekollab.domain.KollabAPI
import org.msc.librekollab.domain.model.Comment
import org.msc.librekollab.domain.model.SearchResult
import org.msc.librekollab.domain.model.anchor.PageAnchor
import org.msc.librekollab.domain.model.anchor.TextAnchor
import org.msc.librekollab.domain.model.change.Change
import org.msc.librekollab.domain.model.change.ChangeAction
import org.msc.librekollab.domain.model.change.ChangeStatus
import org.msc.librekollab.domain.model.image.Image
import org.msc.librekollab.domain.model.image.ImageMeta
import org.msc.librekollab.domain.model.text.MarkIndex
import org.msc.librekollab.domain.model.text.MarkedText
import org.msc.librekollab.domain.model.text.properties.BoldProperty
import org.msc.librekollab.domain.model.text.properties.ItalicProperty
import org.msc.librekollab.domain.model.text.properties.StrikethroughProperty
import org.msc.librekollab.domain.model.text.properties.TextProperty
import org.msc.librekollab.domain.model.text.properties.UnderlineProperty
import kotlin.reflect.KClass
import org.slf4j.LoggerFactory

abstract class CoreKollab(protected val componentContext: XComponentContext) : KollabAPI {

    @Suppress("unused")
    private val log = LoggerFactory.getLogger(CoreKollab::class.java)
    protected val libreOfficeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private data class ActiveRedline(
        val action: ChangeAction,
        val author: String,
        val dateTime: LocalDateTime,
        val start: Int,
        val text: String = ""
    )

    private data class RedlineScan(
        val charOffset: Int = 0,
        val active: ActiveRedline? = null,
        val changes: List<Change> = emptyList()
    )

    private data class MarkedTextScan(
        val offset: Int = 0,
        val inDelete: Boolean = false,
        val inInsert: Boolean = false,
        val text: String = "",
        val properties: List<TextProperty> = emptyList()
    )

    private data class FormattingRun(val text: String, val properties: Set<KClass<out TextProperty>>)

    companion object {
        private const val ANNOTATION_SERVICE = "com.sun.star.text.TextField.Annotation"

        private const val OUTLINE_LEVEL_PROPERTY = "OutlineLevel"

        private const val TEXT_PORTION_TYPE_PROPERTY = "TextPortionType"
        private const val PORTION_TYPE_REDLINE = "Redline"
        private const val PORTION_TYPE_TEXT = "Text"

        private const val IS_START_PROPERTY = "IsStart"
        private const val REDLINE_TYPE_PROPERTY = "RedlineType"
        private const val REDLINE_TYPE_INSERT = "Insert"
        private const val REDLINE_TYPE_DELETE = "Delete"
        private const val REDLINE_AUTHOR_PROPERTY = "RedlineAuthor"
        private const val REDLINE_DATE_TIME_PROPERTY = "RedlineDateTime"

        private const val RECORD_CHANGES_PROPERTY = "RecordChanges"

        private const val AUTHOR_PROPERTY = "Author"
        private const val CONTENT_PROPERTY = "Content"
        private const val DATE_TIME_VALUE_PROPERTY = "DateTimeValue"

        private const val SIZE_PROPERTY = "Size"
        private const val BASE64_INPUT_GROUP_BYTES = 3
        private const val BASE64_OUTPUT_GROUP_CHARS = 4
        private const val PIPE_SERVICE = "com.sun.star.io.Pipe"
        private const val PIPE_CHUNK_SIZE = 65536
        private const val GRAPHIC_EXPORT_FILTER_SERVICE = "com.sun.star.drawing.GraphicExportFilter"
        private const val OUTPUT_STREAM_PROPERTY = "OutputStream"
        private const val MEDIA_TYPE_PROPERTY = "MediaType"
        private const val PNG_MEDIA_TYPE = "image/png"

        private const val CHAR_WEIGHT_PROPERTY = "CharWeight"
        private const val CHAR_POSTURE_PROPERTY = "CharPosture"
        private const val CHAR_UNDERLINE_PROPERTY = "CharUnderline"
        private const val CHAR_STRIKEOUT_PROPERTY = "CharStrikeout"
        private const val BOLD_CHAR_WEIGHT = 150f
        private const val NORMAL_CHAR_WEIGHT = 100f
    }

    abstract override suspend fun listDocuments(): List<String>
    protected abstract suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T
    protected abstract suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T

    protected fun outlineLevel(props: XPropertySet): Int {
        return when (val v = props.getPropertyValue(OUTLINE_LEVEL_PROPERTY)) {
            is Int -> v
            is Short -> v.toInt()
            else -> 0
        }
    }

    protected fun enumerationSequence(enumeration: XEnumeration): Sequence<Any> =
        generateSequence { if (enumeration.hasMoreElements()) enumeration.nextElement() else null }

    override suspend fun getText(documentId: String, changeStatus: ChangeStatus): MarkedText =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc ->
                val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                    .createEnumeration()
                val results = enumerationSequence(paragraphs)
                    .mapIndexed { idx, para -> extractParagraphMarkedText(para, changeStatus, idx) }
                    .toList()
                MarkedText(results.joinToString("\n") { it.first }, results.flatMap { it.second })
            }
        }

    override suspend fun getChanges(documentId: String): List<Change> =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc ->
                val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                    .createEnumeration()
                enumerationSequence(paragraphs)
                    .flatMapIndexed { paragraphIdx, para -> changesInParagraph(para, paragraphIdx) }
                    .toList()
            }
        }

    override suspend fun editText(documentId: String, anchor: TextAnchor, newText: MarkedText, author: String) {
        withEnsuredEditMode(documentId) {
            withContext(libreOfficeDispatcher) {
                withDocumentMutating(documentId) { textDoc ->
                    withAuthor(author) {
                        resolveAnchorRange(textDoc, anchor).setString("")
                        insertFormattedText(textDoc, anchor, newText)
                    }
                }
            }
        }
    }

    protected open fun withAuthor(author: String, block: () -> Unit) = block()

    override suspend fun getPageCount(documentId: String): Int = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            val vc = viewCursorOf(textDoc)
            vc.gotoEnd(false)
            UnoRuntime.queryInterface(XPageCursor::class.java, vc).page.toInt()
        }
    }

    override suspend fun getChapters(documentId: String): List<String> = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                .createEnumeration()
            enumerationSequence(paragraphs)
                .filter { outlineLevel(UnoRuntime.queryInterface(XPropertySet::class.java, it)) > 0 }
                .map { UnoRuntime.queryInterface(XTextRange::class.java, it).string }
                .toList()
        }
    }

    override suspend fun getTextByPages(documentId: String, fromPage: Int, toPage: Int, changeStatus: ChangeStatus): MarkedText =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc ->
                val vc = viewCursorOf(textDoc)
                val pc = UnoRuntime.queryInterface(XPageCursor::class.java, vc)
                val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                    .createEnumeration()
                val results = enumerationSequence(paragraphs)
                    .mapIndexed { idx, element ->
                        vc.gotoRange(UnoRuntime.queryInterface(XTextRange::class.java, element).start, false)
                        Triple(idx, element, pc.page.toInt())
                    }
                    .takeWhile { (_, _, pageNum) -> pageNum <= toPage }
                    .filter { (_, _, pageNum) -> pageNum in fromPage..toPage }
                    .map { (idx, element, _) -> extractParagraphMarkedText(element, changeStatus, idx) }
                    .toList()
                MarkedText(results.joinToString("\n") { it.first }, results.flatMap { it.second })
            }
        }

    override suspend fun getTextByChapter(documentId: String, chapter: String, changeStatus: ChangeStatus): MarkedText =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc ->
                val sb = StringBuilder()
                val properties = mutableListOf<TextProperty>()
                var inChapter = false
                var chapterLevel = 0
                var paraIdx = 0
                val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                    .createEnumeration()
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
                MarkedText(sb.toString().trimEnd(), properties)
            }
        }

    override suspend fun setEditMode(documentId: String, editMode: Boolean): Unit = withContext(libreOfficeDispatcher) {
        withDocumentMutating(documentId) { textDoc ->
            UnoRuntime.queryInterface(XPropertySet::class.java, textDoc)
                .setPropertyValue(RECORD_CHANGES_PROPERTY, editMode)
        }
    }

    override suspend fun getEditMode(documentId: String): Boolean = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            UnoRuntime.queryInterface(XPropertySet::class.java, textDoc)
                .getPropertyValue(RECORD_CHANGES_PROPERTY) as Boolean
        }
    }

    override suspend fun getComments(documentId: String): List<Comment> = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            commentFields(textDoc)
                .map { field -> commentOf(textDoc, field) }
                .toList()
                .sortedWith(compareBy({ it.anchor.paragraphIndex }, { it.anchor.charStart }))
        }
    }

    override suspend fun getComment(documentId: String, commentId: String): Comment? =
        getComments(documentId).find { it.id == commentId }

    override suspend fun updateComment(documentId: String, commentId: String, newText: String): Unit =
        withContext(libreOfficeDispatcher) {
            withDocumentMutating(documentId) { textDoc ->
                val field = findCommentField(textDoc, commentId)
                    ?: throw NoSuchElementException("Comment $commentId not found in document $documentId")
                UnoRuntime.queryInterface(XPropertySet::class.java, field).apply {
                    setPropertyValue(CONTENT_PROPERTY, newText)
                    setPropertyValue(DATE_TIME_VALUE_PROPERTY, unoDateTimeNow())
                }
            }
        }

    override suspend fun deleteComment(documentId: String, commentId: String): Unit =
        withContext(libreOfficeDispatcher) {
            withDocumentMutating(documentId) { textDoc ->
                val field = findCommentField(textDoc, commentId) ?: return@withDocumentMutating
                textDoc.text.removeTextContent(UnoRuntime.queryInterface(XTextContent::class.java, field))
            }
        }

    override suspend fun addComment(documentId: String, commentText: String, author: String, anchor: TextAnchor) {
        withContext(libreOfficeDispatcher) {
            withDocumentMutating(documentId) { textDoc ->
                val anchorRange = resolveAnchorRange(textDoc, anchor)
                val serviceFactory = UnoRuntime.queryInterface(XMultiServiceFactory::class.java, textDoc)
                val annotationField = serviceFactory.createInstance(ANNOTATION_SERVICE)
                UnoRuntime.queryInterface(XPropertySet::class.java, annotationField).apply {
                    setPropertyValue(CONTENT_PROPERTY, commentText)
                    setPropertyValue(AUTHOR_PROPERTY, author)
                    setPropertyValue(DATE_TIME_VALUE_PROPERTY, unoDateTimeNow())
                }
                textDoc.text.insertTextContent(
                    anchorRange,
                    UnoRuntime.queryInterface(XTextContent::class.java, annotationField),
                    true
                )
            }
        }
    }

    override suspend fun getImageMetas(documentId: String): List<ImageMeta> = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            val vc = viewCursorOf(textDoc)
            val pc = UnoRuntime.queryInterface(XPageCursor::class.java, vc)
            getGraphicShapes(textDoc).map { shape ->
                val image = imageOf(textDoc, shape)
                vc.gotoRange(shapeAnchorRange(shape).start, false)
                val page = pc.page.toInt()
                val sizeMb = base64EncodedByteCount(image.bytes.size) / (1024.0 * 1024.0)
                ImageMeta(image.id, image.width, image.height, sizeMb, page, image.textAnchor)
            }
        }
    }

    override suspend fun getImage(documentId: String, imageId: String): Image? = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            getGraphicShapes(textDoc).asSequence().map { shape -> imageOf(textDoc, shape) }.firstOrNull { it.id == imageId }
        }
    }

    override suspend fun search(documentId: String, searchText: String, page: Int, size: Int): SearchResult = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            val vc = viewCursorOf(textDoc)
            val pc = UnoRuntime.queryInterface(XPageCursor::class.java, vc)
            val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text).createEnumeration()
            val allFindings = enumerationSequence(paragraphs)
                .mapIndexed { idx, para -> matchesInParagraph(para, idx, searchText, vc, pc) }
                .flatten()
                .toList()
            val startIndex = (page - 1) * size
            val elements = allFindings.drop(startIndex).take(size)
            SearchResult(page, size, allFindings.size, elements)
        }
    }

    protected suspend fun withEnsuredEditMode(documentId: String, block: suspend () -> Unit) {
        if (!getEditMode(documentId)) setEditMode(documentId, true)
        block()
    }

    protected fun resolveAnchorRange(textDoc: XTextDocument, anchor: TextAnchor): XTextRange {
        val para = paragraphAt(textDoc, anchor.paragraphIndex)
        val paraStart = UnoRuntime.queryInterface(XTextRange::class.java, para).start
        val cursor = textDoc.text.createTextCursorByRange(paraStart)
        cursor.goRight(anchor.charStart.toShort(), false)
        cursor.goRight((anchor.charEnd - anchor.charStart).toShort(), true)
        return cursor
    }

    protected fun buildTextAnchor(textDoc: XTextDocument, anchorRange: XTextRange): TextAnchor {
        val compare = UnoRuntime.queryInterface(XTextRangeCompare::class.java, textDoc.text)
        val paraEnum = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
            .createEnumeration()
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

    private fun commentFields(textDoc: XTextDocument): Sequence<Any> {
        val fieldSupplier = UnoRuntime.queryInterface(XTextFieldsSupplier::class.java, textDoc)
        return enumerationSequence(fieldSupplier.textFields.createEnumeration())
            .filter { UnoRuntime.queryInterface(XServiceInfo::class.java, it).supportsService(ANNOTATION_SERVICE) }
    }

    private fun findCommentField(textDoc: XTextDocument, commentId: String): Any? =
        commentFields(textDoc).firstOrNull { field ->
            val anchorRange = UnoRuntime.queryInterface(XTextContent::class.java, field).anchor
            buildTextAnchor(textDoc, anchorRange).id == commentId
        }

    private fun commentOf(textDoc: XTextDocument, field: Any): Comment {
        val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, field)
        val anchorRange = UnoRuntime.queryInterface(XTextContent::class.java, field).anchor
        val anchor = buildTextAnchor(textDoc, anchorRange)
        val author = propSet.getPropertyValue(AUTHOR_PROPERTY) as String
        val content = propSet.getPropertyValue(CONTENT_PROPERTY) as String
        val dateTime = localDateTimeOf(propSet.getPropertyValue(DATE_TIME_VALUE_PROPERTY) as DateTime)
        return Comment(anchor, author, content, dateTime)
    }

    private fun localDateTimeOf(dt: DateTime): LocalDateTime = LocalDateTime(
        dt.Year.toInt(), dt.Month.toInt(), dt.Day.toInt(),
        dt.Hours.toInt(), dt.Minutes.toInt(), dt.Seconds.toInt()
    )

    private fun unoDateTimeNow(): DateTime {
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

    private fun viewCursorOf(textDoc: XTextDocument): XTextViewCursor {
        val model = UnoRuntime.queryInterface(XModel::class.java, textDoc)
        val vcSupplier = UnoRuntime.queryInterface(XTextViewCursorSupplier::class.java, model.currentController)
        return vcSupplier.viewCursor
    }

    private fun pageCursorOf(textDoc: XTextDocument): XPageCursor =
        UnoRuntime.queryInterface(XPageCursor::class.java, viewCursorOf(textDoc))

    private fun paragraphAt(textDoc: XTextDocument, index: Int): Any {
        val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text).createEnumeration()
        return enumerationSequence(paragraphs).elementAtOrNull(index)
            ?: throw IllegalArgumentException("Paragraph index $index not found in document")
    }

    private fun changesInParagraph(para: Any, paragraphIdx: Int): List<Change> {
        val portions = UnoRuntime.queryInterface(XEnumerationAccess::class.java, para)?.createEnumeration()
            ?: return emptyList()
        return enumerationSequence(portions)
            .fold(RedlineScan()) { state, portion -> scanRedlinePortion(state, portion, paragraphIdx) }
            .changes
    }

    private fun scanRedlinePortion(state: RedlineScan, portion: Any, paragraphIdx: Int): RedlineScan {
        val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, portion)
        return when (propSet.getPropertyValue(TEXT_PORTION_TYPE_PROPERTY) as? String) {
            PORTION_TYPE_REDLINE -> scanRedlineBracket(state, propSet, paragraphIdx)
            PORTION_TYPE_TEXT -> scanRedlineText(state, portion)
            else -> state
        }
    }

    private fun scanRedlineBracket(state: RedlineScan, propSet: XPropertySet, paragraphIdx: Int): RedlineScan {
        val isStart = propSet.getPropertyValue(IS_START_PROPERTY) as? Boolean ?: false
        if (isStart) {
            return startRedline(state, propSet)
        }
        return endRedline(state, paragraphIdx)
    }

    private fun startRedline(state: RedlineScan, propSet: XPropertySet): RedlineScan {
        val action = redlineActionOf(propSet.getPropertyValue(REDLINE_TYPE_PROPERTY) as? String) ?: return state
        val dateTime = (propSet.getPropertyValue(REDLINE_DATE_TIME_PROPERTY) as? DateTime)?.let { localDateTimeOf(it) } ?: return state
        val author = propSet.getPropertyValue(REDLINE_AUTHOR_PROPERTY) as? String ?: ""
        return state.copy(active = ActiveRedline(action, author, dateTime, state.charOffset))
    }

    private fun redlineActionOf(redlineType: String?): ChangeAction? {
        return when (redlineType) {
            REDLINE_TYPE_INSERT -> ChangeAction.INSERT
            REDLINE_TYPE_DELETE -> ChangeAction.DELETE
            else -> null
        }
    }

    private fun endRedline(state: RedlineScan, paragraphIdx: Int): RedlineScan {
        val active = state.active ?: return state
        if (active.text.isEmpty()) {
            return state.copy(active = null)
        }
        val change = Change(
            action = active.action,
            author = active.author,
            dateTime = active.dateTime,
            text = active.text,
            anchor = TextAnchor(active.text, paragraphIdx, active.start, active.start + active.text.length)
        )
        return state.copy(active = null, changes = state.changes + change)
    }

    private fun scanRedlineText(state: RedlineScan, portion: Any): RedlineScan {
        val text = UnoRuntime.queryInterface(XTextRange::class.java, portion)?.string ?: ""
        val active = state.active?.let { it.copy(text = it.text + text) }
        return state.copy(charOffset = state.charOffset + text.length, active = active)
    }

    private fun matchesInParagraph(
        para: Any,
        paragraphIndex: Int,
        searchText: String,
        vc: XTextViewCursor,
        pc: XPageCursor
    ): List<PageAnchor> {
        if (searchText.isEmpty()) {
            return emptyList()
        }
        val (paragraphText, _) = extractParagraphMarkedText(para, ChangeStatus.FUSION, paragraphIndex)
        val matchStarts = findAllOccurrences(paragraphText, searchText)
        if (matchStarts.isEmpty()) {
            return emptyList()
        }
        vc.gotoRange(UnoRuntime.queryInterface(XTextRange::class.java, para).start, false)
        val page = pc.page.toInt()
        return matchStarts.map { start ->
            val matchedText = paragraphText.substring(start, start + searchText.length)
            PageAnchor(TextAnchor(matchedText, paragraphIndex, start, start + searchText.length), page)
        }
    }

    private fun findAllOccurrences(text: String, query: String): List<Int> =
        generateSequence(text.indexOf(query, ignoreCase = true).takeIf { it >= 0 }) { previous ->
            text.indexOf(query, previous + query.length, ignoreCase = true).takeIf { it >= 0 }
        }.toList()

    private fun extractParagraphMarkedText(para: Any, changeStatus: ChangeStatus, paragraphIndex: Int): Pair<String, List<TextProperty>> {
        val portions = UnoRuntime.queryInterface(XEnumerationAccess::class.java, para)?.createEnumeration()
            ?: return Pair("", emptyList())
        val result = enumerationSequence(portions)
            .fold(MarkedTextScan()) { state, portion -> scanMarkedTextPortion(state, portion, changeStatus, paragraphIndex) }
        return Pair(result.text, result.properties)
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

    private fun getGraphicShapes(textDoc: XTextDocument): List<Any> {
        val supplier = UnoRuntime.queryInterface(XTextGraphicObjectsSupplier::class.java, textDoc)
            ?: return emptyList()
        val nameAccess = supplier.graphicObjects
        return nameAccess.elementNames.mapNotNull { nameAccess.getByName(it) }
    }

    private fun shapeAnchorRange(shape: Any): XTextRange =
        UnoRuntime.queryInterface(XTextContent::class.java, shape).anchor

    private fun imageOf(textDoc: XTextDocument, shape: Any): Image {
        val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, shape)
        val size = propSet.getPropertyValue(SIZE_PROPERTY) as Size
        val anchor = buildTextAnchor(textDoc, shapeAnchorRange(shape))
        val bytes = exportShapePng(shape)
        return Image(bytes, size.Width, size.Height, anchor)
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
        val out = java.io.ByteArrayOutputStream()
        val holder = Array(1) { ByteArray(0) }
        while (true) {
            val n = input.readBytes(holder, PIPE_CHUNK_SIZE)
            if (n == 0) break
            out.write(holder[0])
        }
        input.closeInput()
        return out.toByteArray()
    }

    // Inserts newText.text one formatting run at a time, setting the cursor's character properties
    // *before* each insertString call. This avoids ever reformatting already-inserted text: LibreOffice
    // tracks such a later attribute change as its own "Format" redline, which splits what should be one
    // logical Insert into two — losing the second half when getChanges() reads the portions back.
    private fun insertFormattedText(textDoc: XTextDocument, anchor: TextAnchor, markedText: MarkedText) {
        val anchorPara = paragraphAt(textDoc, anchor.paragraphIndex)
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
        if (line.isEmpty()) return emptyList()
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
