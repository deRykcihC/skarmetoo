package com.deryk.skarmetoo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Empty activity that appears in the share drawer.
 * When selected, it confirmed to the user that the image is saved.
 * (In this app, the image is physically saved to the MediaStore before opening the drawer
 * to get the Uri, so this activity just confirms that storage location as a discrete action).
 */
class SaveToGalleryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val streamUri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        if (streamUri != null) {
            // Trigger a media scan to ensure it shows up in Gallery immediately
            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(getRealPathFromURI(streamUri)),
                null
            ) { path, uri ->
                runOnUiThread {
                    Toast.makeText(this, "Saved to: $path", Toast.LENGTH_LONG).show()
                }
            }
        }
        
        finish()
    }

    private fun getRealPathFromURI(contentUri: android.net.Uri): String? {
        val cursor = contentResolver.query(contentUri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.MediaStore.Images.ImageColumns.DATA)
                if (idx >= 0) it.getString(idx) else contentUri.path
            } else contentUri.path
        } ?: contentUri.path
    }
}
