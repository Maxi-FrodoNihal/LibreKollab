package org.msc.liberekollab.adapter

import io.github.cdimascio.dotenv.dotenv
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.msc.liberekollab.abstrakt.IOAPI
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class MinioAdapter(
    host: String,
    port: Int,
    accessKey: String,
    secretKey: String,
    private val bucket: String
) : IOAPI {

    constructor() : this(
        host = dotenv { ignoreIfMissing = true }["MINIO_HOST"],
        port = dotenv { ignoreIfMissing = true }["MINIO_PORT"].toInt(),
        accessKey = dotenv { ignoreIfMissing = true }["MINIO_ACCESS_KEY"],
        secretKey = dotenv { ignoreIfMissing = true }["MINIO_SECRET_KEY"],
        bucket = dotenv { ignoreIfMissing = true }["MINIO_BUCKET"]
    )

    private val client = MinioClient.builder()
        .endpoint("http://$host:$port")
        .credentials(accessKey, secretKey)
        .build()

    init {
        val exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
        }
    }

    override fun nextId(): String = UUID.randomUUID().toString()

    override suspend fun upload(documentId: String, fileName: String, content: InputStream): String =
        withContext(Dispatchers.IO) {
            val objectName = "${documentId}_${fileName}"
            val bytes = content.readBytes()
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .`object`(objectName)
                    .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
                    .build()
            )
            documentId
        }

    override suspend fun download(documentId: String): OutputStream =
        withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            client.getObject(
                GetObjectArgs.builder()
                    .bucket(bucket)
                    .`object`(resolveObjectName(documentId))
                    .build()
            ).use { it.copyTo(out) }
            out
        }

    override suspend fun delete(documentId: String): Unit =
        withContext(Dispatchers.IO) {
            client.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .`object`(resolveObjectName(documentId))
                    .build()
            )
        }

    override suspend fun ls(): List<String> =
        withContext(Dispatchers.IO) {
            client.listObjects(
                ListObjectsArgs.builder()
                    .bucket(bucket)
                    .build()
            ).map { it.get().objectName() }
        }

    private fun resolveObjectName(documentId: String): String =
        client.listObjects(
            ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(documentId)
                .build()
        ).first().get().objectName()
}
