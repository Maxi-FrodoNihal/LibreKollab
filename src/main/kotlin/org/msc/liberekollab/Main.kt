package org.msc.liberekollab

import org.msc.liberekollab.logging.LoggingIOAPI
import org.msc.liberekollab.logging.LoggingKollabAPI
import org.msc.liberekollab.adapter.MinioAdapter

fun main() {
    KtorServer(
        io = LoggingIOAPI(MinioAdapter()),
        kollab = LoggingKollabAPI(LibereKollab())
    ).start()
}
