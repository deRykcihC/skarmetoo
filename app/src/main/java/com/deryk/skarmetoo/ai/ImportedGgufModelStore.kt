package com.deryk.skarmetoo.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImportedGgufModelStore {
  enum class FileRole {
    MODEL,
    MMPROJ,
  }

  private const val PREFS_NAME = "skarmetoo_prefs"
  private const val MODEL_PATH_KEY = "imported_gguf_model_path"
  private const val MMPROJ_PATH_KEY = "imported_gguf_mmproj_path"
  private const val IMPORT_DIR = "custom_gguf"

  fun getModelFile(context: Context): File? = getStoredFile(context, MODEL_PATH_KEY)

  fun getMmprojFile(context: Context): File? = getStoredFile(context, MMPROJ_PATH_KEY)

  fun getModelInfo(context: Context): GgufModelInfo? {
    val modelFile = getModelFile(context) ?: return null
    val mmprojFile = getMmprojFile(context) ?: return null
    return GgufModelInfo(
        displayName = modelFile.nameWithoutExtension,
        fileName = modelFile.relativeTo(context.filesDir).path,
        hfRepo = "",
        hfFile = "",
        sizeMb = (modelFile.length() / (1024L * 1024L)).coerceAtLeast(1L),
        description = "Imported GGUF vision model",
        isVision = true,
        mmprojFile = mmprojFile.relativeTo(context.filesDir).path,
        mmprojSizeMb = (mmprojFile.length() / (1024L * 1024L)).coerceAtLeast(1L),
        quantLabel = "Imported",
    )
  }

  suspend fun importFile(context: Context, uri: Uri, role: FileRole): File =
      withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri)
        val lowerName = displayName.lowercase()
        require(lowerName.endsWith(".gguf")) { "Select a .gguf file" }
        when (role) {
          FileRole.MODEL ->
              require(!lowerName.contains("mmproj")) { "Select the main model, not an mmproj file" }
          FileRole.MMPROJ -> require(lowerName.contains("mmproj")) { "Select an mmproj .gguf file" }
        }

        val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val importDir = File(context.filesDir, IMPORT_DIR)
        check(importDir.exists() || importDir.mkdirs()) { "Could not create the model folder" }
        val destination = File(importDir, safeName)
        val temporary = File(importDir, "$safeName.tmp")

        try {
          context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the selected file" }
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
          }
          check(temporary.length() > 0L) { "The selected file is empty" }
          if (destination.exists() && !destination.delete()) {
            error("Could not replace the existing file")
          }
          if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
          }

          val key = if (role == FileRole.MODEL) MODEL_PATH_KEY else MMPROJ_PATH_KEY
          val relativePath = destination.relativeTo(context.filesDir).path
          context
              .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
              .edit()
              .putString(key, relativePath)
              .apply()
          destination
        } catch (e: Exception) {
          temporary.delete()
          throw e
        }
      }

  private fun getStoredFile(context: Context, key: String): File? {
    val relativePath =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null)
            ?: return null
    val file = File(context.filesDir, relativePath)
    return file.takeIf { it.isFile && it.length() > 0L }
  }

  private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0) {
              val name = cursor.getString(column)
              if (!name.isNullOrBlank()) return name
            }
          }
        }
    throw IllegalArgumentException("The selected file has no name")
  }
}
