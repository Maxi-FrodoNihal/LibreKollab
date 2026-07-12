package org.msc.librekollab.adapter.libreoffice.component

import com.sun.star.beans.XPropertySet
import com.sun.star.lang.XMultiServiceFactory
import com.sun.star.lang.XServiceInfo
import com.sun.star.text.XTextContent
import com.sun.star.text.XTextDocument
import com.sun.star.text.XTextFieldsSupplier
import com.sun.star.uno.UnoRuntime
import com.sun.star.util.DateTime
import org.msc.librekollab.adapter.libreoffice.UnoClient
import org.msc.librekollab.domain.model.Comment
import org.msc.librekollab.domain.model.anchor.TextAnchor

class CommentComponent(private val unoClient: UnoClient) {

    companion object {
        private const val ANNOTATION_SERVICE = "com.sun.star.text.TextField.Annotation"
        private const val AUTHOR_PROPERTY = "Author"
        private const val CONTENT_PROPERTY = "Content"
        private const val DATE_TIME_VALUE_PROPERTY = "DateTimeValue"
    }

    fun getComments(textDoc: XTextDocument): List<Comment> =
        commentFields(textDoc)
            .map { field -> commentOf(textDoc, field) }
            .toList()
            .sortedWith(compareBy({ it.anchor.paragraphIndex }, { it.anchor.charStart }))

    fun updateComment(textDoc: XTextDocument, documentId: String, commentId: String, newText: String) {
        val field = findCommentField(textDoc, commentId)
            ?: throw NoSuchElementException("Comment $commentId not found in document $documentId")
        UnoRuntime.queryInterface(XPropertySet::class.java, field).apply {
            setPropertyValue(CONTENT_PROPERTY, newText)
            setPropertyValue(DATE_TIME_VALUE_PROPERTY, unoClient.unoDateTimeNow())
        }
    }

    fun deleteComment(textDoc: XTextDocument, documentId: String, commentId: String) {
        val field = findCommentField(textDoc, commentId)
            ?: throw NoSuchElementException("Comment $commentId not found in document $documentId")
        textDoc.text.removeTextContent(UnoRuntime.queryInterface(XTextContent::class.java, field))
    }

    fun addComment(textDoc: XTextDocument, commentText: String, author: String, anchor: TextAnchor) {
        val anchorRange = unoClient.resolveAnchorRange(textDoc, anchor)
        val serviceFactory = UnoRuntime.queryInterface(XMultiServiceFactory::class.java, textDoc)
        val annotationField = serviceFactory.createInstance(ANNOTATION_SERVICE)
        UnoRuntime.queryInterface(XPropertySet::class.java, annotationField).apply {
            setPropertyValue(CONTENT_PROPERTY, commentText)
            setPropertyValue(AUTHOR_PROPERTY, author)
            setPropertyValue(DATE_TIME_VALUE_PROPERTY, unoClient.unoDateTimeNow())
        }
        textDoc.text.insertTextContent(
            anchorRange,
            UnoRuntime.queryInterface(XTextContent::class.java, annotationField),
            true
        )
    }

    private fun commentFields(textDoc: XTextDocument): Sequence<Any> {
        val fieldSupplier = UnoRuntime.queryInterface(XTextFieldsSupplier::class.java, textDoc)
        return unoClient.enumerationSequence(fieldSupplier.textFields.createEnumeration())
            .filter { UnoRuntime.queryInterface(XServiceInfo::class.java, it).supportsService(ANNOTATION_SERVICE) }
    }

    private fun findCommentField(textDoc: XTextDocument, commentId: String): Any? =
        commentFields(textDoc).firstOrNull { field -> commentOf(textDoc, field).id == commentId }

    private fun commentOf(textDoc: XTextDocument, field: Any): Comment {
        val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, field)
        val anchorRange = UnoRuntime.queryInterface(XTextContent::class.java, field).anchor
        val anchor = unoClient.buildTextAnchor(textDoc, anchorRange)
        val author = propSet.getPropertyValue(AUTHOR_PROPERTY) as String
        val content = propSet.getPropertyValue(CONTENT_PROPERTY) as String
        val dateTime = unoClient.localDateTimeOf(propSet.getPropertyValue(DATE_TIME_VALUE_PROPERTY) as DateTime)
        return Comment(anchor, author, content, dateTime)
    }
}
