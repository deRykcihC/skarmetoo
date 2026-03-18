package com.deryk.skarmetoo

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SaveToGalleryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriStr = intent.getStringExtra("EXTRA_IMAGE_URI")
        if (uriStr == null) {
            finish()
            return
        }
        val uri = Uri.parse(uriStr)
        val appContext = applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resolver = appContext.contentResolver
                val contentValues =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "skarmetoo_share_${System.currentTimeMillis()}.png")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                        }
                    }
                val imageUriOut = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUriOut != null) {
                    resolver.openInputStream(uri)?.use { input ->
                        resolver.openOutputStream(imageUriOut)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, appContext.getString(R.string.saved_to_gallery), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.share_failed, "Failed to insert"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.share_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }
}
