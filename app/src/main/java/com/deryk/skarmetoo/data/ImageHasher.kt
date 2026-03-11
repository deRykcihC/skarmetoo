package com.deryk.skarmetoo.data

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Perceptual hash (dHash) for cross-device image recognition.
 * Produces a 64-bit hash based on image content, not metadata.
 * Same image on different devices → same hash.
 */
object ImageHasher {

    /**
     * Compute a difference hash (dHash) for the given bitmap.
     * 1. Resize to 9x8 grayscale
     * 2. Compare adjacent pixels horizontally
     * 3. Produces a 16-char hex string (64 bits)
     */
    fun computeDHash(bitmap: Bitmap): String {
        // Resize to 9 wide x 8 tall
        val resized = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val bits = StringBuilder()

        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val leftGray = getGrayValue(resized.getPixel(x, y))
                val rightGray = getGrayValue(resized.getPixel(x + 1, y))
                bits.append(if (leftGray > rightGray) '1' else '0')
            }
        }

        // Convert 64-bit binary string to 16-char hex
        // Process in 4-bit chunks to avoid overflow
        val hex = StringBuilder()
        for (i in 0 until 64 step 4) {
            val nibble = bits.substring(i, i + 4).toInt(2)
            hex.append(Integer.toHexString(nibble))
        }
        return hex.toString()
    }

    /**
     * Compute hamming distance between two hashes.
     * Lower = more similar. 0 = identical.
     * Typically < 10 means same image with minor differences.
     */
    fun hammingDistance(hash1: String, hash2: String): Int {
        if (hash1.length != hash2.length) return Int.MAX_VALUE
        var distance = 0
        for (i in hash1.indices) {
            val v1 = Integer.parseInt(hash1[i].toString(), 16)
            val v2 = Integer.parseInt(hash2[i].toString(), 16)
            var xor = v1 xor v2
            while (xor != 0) {
                distance += xor and 1
                xor = xor shr 1
            }
        }
        return distance
    }

    private fun getGrayValue(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
