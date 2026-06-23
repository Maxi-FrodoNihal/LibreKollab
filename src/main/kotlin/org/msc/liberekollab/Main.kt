package org.msc.liberekollab

import io.github.cdimascio.dotenv.dotenv
import org.msc.liberekollab.logging.LoggingIOAPI
import org.msc.liberekollab.logging.LoggingKollabAPI
import org.msc.liberekollab.adapter.MinioAdapter
import kotlin.text.toInt

fun main() {
    val port = dotenv { ignoreIfMissing = true }.get("APP_PORT")?.toInt() ?: 8080
    KtorServer(
        io = LoggingIOAPI(MinioAdapter()),
        kollab = LoggingKollabAPI(LibereKollab())
    ).start(port)
}
