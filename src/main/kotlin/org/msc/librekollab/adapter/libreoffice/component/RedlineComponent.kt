package org.msc.librekollab.adapter.libreoffice.component

import com.sun.star.beans.XPropertySet
import com.sun.star.container.XEnumerationAccess
import com.sun.star.text.XTextRange
import com.sun.star.uno.UnoRuntime
import com.sun.star.util.DateTime
import kotlinx.datetime.LocalDateTime
import org.msc.librekollab.adapter.libreoffice.UnoClient
import org.msc.librekollab.domain.model.anchor.TextAnchor
import org.msc.librekollab.domain.model.change.Change
import org.msc.librekollab.domain.model.change.ChangeAction

class RedlineComponent(private val unoClient: UnoClient) {

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

    companion object {
        private const val TEXT_PORTION_TYPE_PROPERTY = "TextPortionType"
        private const val PORTION_TYPE_REDLINE = "Redline"
        private const val PORTION_TYPE_TEXT = "Text"

        private const val IS_START_PROPERTY = "IsStart"
        private const val REDLINE_TYPE_PROPERTY = "RedlineType"
        private const val REDLINE_TYPE_INSERT = "Insert"
        private const val REDLINE_TYPE_DELETE = "Delete"
        private const val REDLINE_AUTHOR_PROPERTY = "RedlineAuthor"
        private const val REDLINE_DATE_TIME_PROPERTY = "RedlineDateTime"
    }

    fun changesInParagraph(para: Any, paragraphIdx: Int): List<Change> {
        val portions = UnoRuntime.queryInterface(XEnumerationAccess::class.java, para)?.createEnumeration()
            ?: return emptyList()
        return unoClient.enumerationSequence(portions)
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
        val dateTime = (propSet.getPropertyValue(REDLINE_DATE_TIME_PROPERTY) as? DateTime)?.let { unoClient.localDateTimeOf(it) } ?: return state
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
}
