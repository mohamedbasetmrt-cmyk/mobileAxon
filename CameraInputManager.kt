package com.example.app_abdelbaset

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import android.util.Base64
import androidx.exifinterface.media.ExifInterface


data class CapturedImage(
    val uri: Uri,
    val base64: String,    // جاهز تبعته في أي API
    val bitmap: Bitmap
)

/**
 * استخدامه في ChatScreen:
 *
 *   val cameraManager = rememberCameraInputManager(
 *       onImageCaptured = { captured -> pendingImage = captured }
 *   )
 *
 *   // لفتح الكاميرا:
 *   cameraManager.launch()
 *
 *   // في الـ UI عشان تعرض preview:
 *   cameraManager.pendingImage?.let { ... }
 */
@Composable
fun rememberCameraInputManager(
    onImageCaptured: (CapturedImage) -> Unit,
    onError: (String) -> Unit = {}
): CameraInputManager {
    val context = LocalContext.current
    var tempUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val uri = tempUri ?: return@rememberLauncherForActivityResult
            try {
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: return@rememberLauncherForActivityResult

                // Resize لو الصورة كبيرة — max 1024px على أطول ضلع
                val corrected = correctBitmapRotation(bitmap, uri, context)  // ← جديد
                val scaled    = scaleBitmap(corrected, 1024)

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                onImageCaptured(CapturedImage(uri = uri, base64 = base64, bitmap = scaled))
            } catch (e: Exception) {
                onError("Failed to process image: ${e.message}")
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCamera(context, cameraLauncher) { tempUri = it }
        else onError("Camera permission denied")
    }

    return remember {
        CameraInputManager(
            context        = context,
            onLaunchCamera = {
                val hasPerm = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                if (hasPerm) openCamera(context, cameraLauncher) { tempUri = it }
                else permLauncher.launch(Manifest.permission.CAMERA)
            }
        )
    }
}

class CameraInputManager(
    val context: Context,
    private val onLaunchCamera: () -> Unit
) {
    fun launch() = onLaunchCamera()
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun openCamera(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Uri>,
    onUri: (Uri) -> Unit
) {
    val file = File.createTempFile("axon_img_", ".jpg", context.cacheDir)
    val uri  = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
    )
    onUri(uri)
    launcher.launch(uri)
}

private fun scaleBitmap(bitmap: Bitmap, maxPx: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= maxPx && h <= maxPx) return bitmap
    val ratio = maxPx.toFloat() / maxOf(w, h)
    return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
}

private fun correctBitmapRotation(bitmap: Bitmap, uri: Uri, context: Context): Bitmap {
    val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
    val exif = ExifInterface(inputStream)
    inputStream.close()

    val rotation = when (
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90  -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else                                 -> 0f
    }

    if (rotation == 0f) return bitmap

    val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}