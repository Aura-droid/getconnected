package com.example.getconnected

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Client-side image compression used before every Cloudinary upload.
 *
 * Free-tier friendly: keeps uploads small on slow connections and avoids
 * burning Cloudinary transformation/storage credits on full-resolution photos.
 *
 * Decodes with a safe sample size, scales to [maxDim] px on the longest edge,
 * re-encodes as JPEG [quality] and bakes in the EXIF rotation (re-encoding
 * strips the metadata, so without this camera photos would appear sideways).
 *
 * Always returns a Uri that is safe to upload: on any failure the original
 * [uri] is returned unchanged so the upload still proceeds.
 */
object ImageCompressor {

    private const val TAG = "ImageCompressor"

    /** Blocking disk/decode work — call from Dispatchers.IO. */
    fun compress(
        context: Context,
        uri: Uri,
        maxDim: Int = 1024,
        quality: Int = 80
    ): Uri {
        return try {
            val resolver = context.contentResolver

            // 1. Bounds-only decode to measure the image without allocating pixels.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return uri

            // 2. Power-of-two sample size so huge photos never OOM.
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= maxDim ||
                bounds.outHeight / (sampleSize * 2) >= maxDim
            ) {
                sampleSize *= 2
            }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize }
                )
            } ?: return uri

            // 3. Scale exactly to the target size.
            val longest = maxOf(decoded.width, decoded.height)
            val bitmap = if (longest > maxDim) {
                val scale = maxDim.toFloat() / longest
                val w = (decoded.width * scale).toInt().coerceAtLeast(1)
                val h = (decoded.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, w, h, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            // 4. Bake in EXIF orientation.
            val oriented = applyExifOrientation(context, uri, bitmap)

            // 5. Write JPEG to cache.
            val outFile = File(context.cacheDir, "gc_upload_${System.currentTimeMillis()}.jpg")
            val outW = oriented.width
            val outH = oriented.height
            try {
                FileOutputStream(outFile).use { out ->
                    oriented.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
            } finally {
                oriented.recycle()
            }

            Log.d(
                TAG,
                "compressed ${bounds.outWidth}x${bounds.outHeight} (sample $sampleSize) " +
                    "-> ${outW}x${outH}, " +
                    "${outFile.length() / 1024} KB, q$quality"
            )
            Uri.fromFile(outFile)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM while compressing, uploading original", e)
            uri
        } catch (e: Exception) {
            Log.w(TAG, "compress failed, uploading original", e)
            uri
        }
    }

    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap // already upright
        }

        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } catch (e: Exception) {
            bitmap // keep the unrotated bitmap rather than failing the upload
        }
    }
}
