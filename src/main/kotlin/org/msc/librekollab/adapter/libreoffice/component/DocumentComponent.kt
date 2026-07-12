package org.msc.librekollab.adapter.libreoffice.component

import com.sun.star.text.XTextDocument

interface DocumentComponent {
    suspend fun listDocuments(): List<String>
    suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T
    suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T
}
