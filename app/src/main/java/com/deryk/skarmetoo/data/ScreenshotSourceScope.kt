package com.deryk.skarmetoo.data

import java.net.URI
import java.net.URLDecoder

/** Compares document identities, so overlapping tree grants do not erase each other's images. */
object ScreenshotSourceScope {
  fun revokedTrees(selected: Set<String>, readableGrants: Set<String>): Set<String> =
      selected.filterTo(linkedSetOf()) { folder ->
        readableGrants.none { grant -> grant == folder || isWithinTree(folder, grant) }
      }

  fun isWithinTree(imageUri: String, treeUri: String): Boolean =
      runCatching {
            val image = URI(imageUri)
            val tree = URI(treeUri)
            if (image.authority != tree.authority) return false
            val root = documentId(tree, "tree") ?: return false
            // Other document providers may use opaque child IDs rather than filesystem paths.
            if (image.authority != "com.android.externalstorage.documents" &&
                documentId(image, "tree") == root)
                return true
            val document =
                documentId(image, "document") ?: documentId(image, "tree") ?: return false
            document == root ||
                document.startsWith(root.trimEnd('/') + "/") ||
                (root.endsWith(":") && document.startsWith(root))
          }
          .getOrDefault(false)

  private fun documentId(uri: URI, marker: String): String? {
    val segments = uri.rawPath.orEmpty().split('/')
    val index = segments.indexOf(marker)
    if (index < 0 || index + 1 >= segments.size) return null
    // '+' is a literal character in a URI path, not a form-encoded space.
    return URLDecoder.decode(segments[index + 1].replace("+", "%2B"), "UTF-8")
  }
}
