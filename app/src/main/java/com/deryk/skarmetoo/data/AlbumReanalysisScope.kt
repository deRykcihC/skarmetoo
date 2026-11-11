package com.deryk.skarmetoo.data

import android.net.Uri
import android.provider.DocumentsContract

/** Matches albums by image identity, never by similarity hash or just a filename. */
internal object AlbumReanalysisScope {
  fun documentIdForPath(path: String, primaryRoot: String): String? {
    val root = primaryRoot.trimEnd('/')
    if (path.startsWith("$root/")) return "primary:${path.removePrefix("$root/")}"
    val match = Regex("^/storage/([^/]+)/(.+)$").matchEntire(path) ?: return null
    return "${match.groupValues[1].lowercase()}:${match.groupValues[2]}"
  }

  private fun documentIdForUri(value: String): String? =
      runCatching {
            val uri = Uri.parse(value)
            if (uri.authority != "com.android.externalstorage.documents") return null
            val id = DocumentsContract.getDocumentId(uri)
            val colon = id.indexOf(':')
            if (colon < 0) null else id.substring(0, colon).lowercase() + id.substring(colon)
          }
          .getOrNull()

  fun entryIds(
      entries: List<ScreenshotEntry>,
      mediaUris: Set<String>,
      documentIds: Set<String>,
  ): Set<Long> =
      entries
          .asSequence()
          .filter {
            val hasContext = it.analyzedAt > 0L || it.summary.isNotBlank() || it.tags.isNotBlank()
            hasContext &&
                it.imageUri.isNotBlank() &&
                (it.imageUri in mediaUris ||
                    documentIdForUri(it.imageUri)?.let(documentIds::contains) == true)
          }
          .mapTo(linkedSetOf()) { it.id }
}
