package com.notebook.app.data.assets

import kotlinx.serialization.json.*

/**
 * Deterministic serialization of the SAME detached source captured by the atomic
 * hold transaction. These bytes belong to a private, UNTRUSTED stage only;
 * they are not a portable .notebook archive or a receipt.
 */
internal object ExportCapturedDocumentCodec {
    fun snapshot(operationId: String, source: SnapshotResult.Complete): ByteArray =
        buildJsonObject {
            put("format", "internal-untrusted-captured-note-v1")
            put("operationId", operationId)
            put("note", buildJsonObject {
                put("id", source.note.id)
                put("notebookId", source.note.notebookId)
                put("title", source.note.title)
                put("revision", source.note.revision)
                put("createdAtMillis", source.note.createdAtMillis)
                put("updatedAtMillis", source.note.updatedAtMillis)
                source.note.lastOpenedAtMillis?.let { put("lastOpenedAtMillis", it) }
            })
            put("pages", buildJsonArray {
                for (page in source.pages) add(buildJsonObject {
                    put("id", page.id)
                    put("noteId", page.noteId)
                    put("orderIndex", page.orderIndex)
                    put("width", page.width)
                    put("height", page.height)
                    put("revision", page.revision)
                    put("canonicalBackground", page.canonicalBackground)
                    put("elements", buildJsonArray {
                        for (element in page.elements) add(buildJsonObject {
                            put("id", element.id)
                            put("pageId", element.pageId)
                            put("orderIndex", element.orderIndex)
                            put("kind", element.kind)
                            put("revision", element.revision)
                            put("canonicalEnvelope", element.canonicalEnvelope)
                        })
                    })
                })
            })
            put("imageOwners", buildJsonArray {
                for (owner in source.imageOwners) add(buildJsonObject {
                    put("noteId", owner.noteId)
                    put("pageId", owner.pageId)
                    put("elementId", owner.elementId)
                    put("assetId", owner.assetId)
                })
            })
            put("assets", buildJsonArray {
                for (asset in source.assets) add(buildJsonObject {
                    put("id", asset.id)
                    put("sha256", asset.sha256)
                    put("mimeType", asset.mimeType)
                    put("encodedBytes", asset.encodedBytes)
                    put("state", asset.state)
                    // Path provenance is retained as data, never trusted as a destination.
                    put("registeredRelativePath", asset.relativePath)
                })
            })
        }.toString().toByteArray(Charsets.UTF_8)

    fun manifest(
        operationId: String,
        source: SnapshotResult.Complete,
        snapshotSha256: String,
        copies: List<ExportPrivateCopyObservation>,
    ): ByteArray = buildJsonObject {
        put("format", "internal-untrusted-copy-manifest-v1")
        put("operationId", operationId)
        put("noteId", source.note.id)
        put("noteRevision", source.note.revision)
        put("snapshotSha256", snapshotSha256)
        put("copies", buildJsonArray {
            for (copy in copies.sortedBy { it.assetId }) add(buildJsonObject {
                put("assetId", copy.assetId)
                put("fileName", copy.fileName)
                put("byteCount", copy.byteCount)
                put("sha256", copy.sha256)
            })
        })
    }.toString().toByteArray(Charsets.UTF_8)
}
