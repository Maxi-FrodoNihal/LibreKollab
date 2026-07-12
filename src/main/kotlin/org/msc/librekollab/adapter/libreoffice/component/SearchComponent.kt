package org.msc.librekollab.adapter.libreoffice.component

import com.sun.star.container.XEnumerationAccess
import com.sun.star.text.XPageCursor
import com.sun.star.text.XTextDocument
import com.sun.star.text.XTextRange
import com.sun.star.text.XTextViewCursor
import com.sun.star.uno.UnoRuntime
import org.msc.librekollab.adapter.libreoffice.UnoClient
import org.msc.librekollab.domain.model.SearchResult
import org.msc.librekollab.domain.model.anchor.PageAnchor
import org.msc.librekollab.domain.model.anchor.TextAnchor

class SearchComponent(private val unoClient: UnoClient) {

    fun search(
        textDoc: XTextDocument,
        searchText: String,
        page: Int,
        size: Int,
        paragraphTextOf: (Any, Int) -> String
    ): SearchResult {
        val vc = unoClient.viewCursorOf(textDoc)
        val pc = unoClient.pageCursorOf(textDoc)
        val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text).createEnumeration()
        val allFindings = unoClient.enumerationSequence(paragraphs)
            .mapIndexed { idx, para -> matchesInParagraph(para, idx, searchText, vc, pc, paragraphTextOf) }
            .flatten()
            .toList()
        val startIndex = (page - 1) * size
        val elements = allFindings.drop(startIndex).take(size)
        return SearchResult(page, size, allFindings.size, elements)
    }

    private fun matchesInParagraph(
        para: Any,
        paragraphIndex: Int,
        searchText: String,
        vc: XTextViewCursor,
        pc: XPageCursor,
        paragraphTextOf: (Any, Int) -> String
    ): List<PageAnchor> {
        if (searchText.isEmpty()) {
            return emptyList()
        }
        val paragraphText = paragraphTextOf(para, paragraphIndex)
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

    private fun findAllOccurrences(text: String, query: String): List<Int> {
        if (query.isEmpty()) {
            return emptyList()
        }
        return generateSequence(text.indexOf(query, ignoreCase = true).takeIf { it >= 0 }) { previous ->
            text.indexOf(query, previous + query.length, ignoreCase = true).takeIf { it >= 0 }
        }.toList()
    }
}
