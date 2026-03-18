package com.deryk.skarmetoo

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.ui.theme.SkarmetooTheme
import kotlinx.coroutines.*
import android.graphics.Color as AndroidColor

object ShareUtils {
    @OptIn(ExperimentalLayoutApi::class)
    fun shareScreenshotContent(
        context: Context,
        entry: ScreenshotEntry,
        noteText: String,
    ) {
        Toast.makeText(context, "Rendering image for sharing...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val baseBitmap =
                    withContext(Dispatchers.IO) {
                        try {
                            val uri = Uri.parse(entry.imageUri)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                ImageDecoder.decodeBitmap(
                                    ImageDecoder.createSource(context.contentResolver, uri),
                                ) { dec, _, _ -> dec.isMutableRequired = true }
                            } else {
                                @Suppress("DEPRECATION")
                                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }

                // Color picking using Palette
                val palette = baseBitmap?.let { Palette.from(it).generate() }
                val themeColorInt =
                    palette?.getVibrantColor(0xFF2196F3.toInt())
                        ?: palette?.getDominantColor(0xFF2196F3.toInt())
                        ?: 0xFF2196F3.toInt()
                val themeColor = androidx.compose.ui.graphics.Color(themeColorInt)
                val themeColorLight = themeColor.copy(alpha = 0.15f)

                val imageBitmap =
                    baseBitmap?.let {
                        val maxDim = 1080
                        if (it.width > maxDim || it.height > maxDim) {
                            val scale = kotlin.math.min(maxDim.toFloat() / it.width, maxDim.toFloat() / it.height)
                            Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true)
                        } else {
                            it
                        }
                    }?.asImageBitmap()

                var currentContext = context
                while (currentContext is ContextWrapper && currentContext !is Activity) {
                    currentContext = currentContext.baseContext
                }
                val activity = currentContext as Activity
                val rootViewGroup = activity.findViewById<ViewGroup>(android.R.id.content)

                val composeView =
                    ComposeView(activity).apply {
                        setContent {
                            SkarmetooTheme(darkTheme = false) { // Force light theme for white background consistency
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .background(androidx.compose.ui.graphics.Color.White)
                                            .padding(24.dp),
                                ) {
                                    Text(
                                        "Skarmetoo",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = themeColor,
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    if (imageBitmap != null) {
                                        Image(
                                            bitmap = imageBitmap,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                                            contentScale = ContentScale.FillWidth,
                                        )
                                        Spacer(modifier = Modifier.height(20.dp))
                                    }

                                    if (entry.summary.isNotBlank()) {
                                        Text(
                                            entry.summary,
                                            style =
                                                MaterialTheme.typography.bodyLarge.copy(
                                                    hyphens = Hyphens.Auto,
                                                    lineBreak = LineBreak.Paragraph,
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                ),
                                            color = androidx.compose.ui.graphics.Color.Black,
                                            textAlign = TextAlign.Justify,
                                        )
                                        Spacer(modifier = Modifier.height(20.dp))
                                    }

                                    if (entry.getTagList().isNotEmpty()) {
                                        Text("TAGS", style = MaterialTheme.typography.labelLarge, color = themeColor)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            entry.getTagList().forEach { tag ->
                                                Surface(
                                                    shape = RoundedCornerShape(16.dp),
                                                    color = themeColorLight,
                                                ) {
                                                    Text(
                                                        tag,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                        color = themeColor,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium,
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(20.dp))
                                    }

                                    if (noteText.isNotBlank()) {
                                        Text("NOTE", style = MaterialTheme.typography.labelLarge, color = themeColor)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            noteText,
                                            style =
                                                MaterialTheme.typography.bodyMedium.copy(
                                                    hyphens = Hyphens.Auto,
                                                    lineBreak = LineBreak.Paragraph,
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                ),
                                            color = androidx.compose.ui.graphics.Color.DarkGray,
                                            textAlign = TextAlign.Justify,
                                        )
                                        Spacer(modifier = Modifier.height(20.dp))
                                    }
                                }
                            }
                        }
                    }

                // Off-screen layout
                val widthPx = (380 * context.resources.displayMetrics.density).toInt()
                composeView.layoutParams = FrameLayout.LayoutParams(widthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
                composeView.translationX = -100000f
                composeView.translationY = -100000f
                rootViewGroup.addView(composeView)

                delay(800) // Increase delay for complex layout stabilization

                // Force measure and layout to get content-dependent height
                val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                composeView.measure(widthSpec, heightSpec)
                composeView.layout(0, 0, composeView.measuredWidth, composeView.measuredHeight)

                if (composeView.measuredWidth > 0 && composeView.measuredHeight > 0) {
                    val finalBitmap = Bitmap.createBitmap(composeView.measuredWidth, composeView.measuredHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(finalBitmap)
                    // Draw solid white background
                    canvas.drawColor(AndroidColor.WHITE)
                    composeView.draw(canvas)

                    rootViewGroup.removeView(composeView)

                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
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
                            resolver.openOutputStream(imageUriOut)?.use { out ->
                                finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }

                            val intent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, imageUriOut)
                                }
                            context.startActivity(Intent.createChooser(intent, "Share Analysis"))
                        }
                    }
                } else {
                    rootViewGroup.removeView(composeView)
                    Toast.makeText(context, "Failed to capture window bounds", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
