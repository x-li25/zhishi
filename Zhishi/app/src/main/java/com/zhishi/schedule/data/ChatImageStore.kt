package com.zhishi.schedule.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 保存重编码后的图片到应用私有目录，聊天 JSON 只存文件名和日程识别结果。 */
class ChatImageStore(private val context: Context) {
    private val directory = File(context.filesDir, "chat_images").apply { mkdirs() }

    suspend fun importImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                require(output.size() + count <= MAX_SOURCE_BYTES) { "图片超过20MB，请先裁剪或缩小后再选择" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取图片，请重新选择")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片格式，请选择 JPG、PNG 或 WebP 图片" }
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > MAX_DECODE_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片解码失败，请换一张图片")
        var oriented: Bitmap = decoded
        var normalized: Bitmap? = null
        try {
            val orientation = runCatching {
                bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = orientationMatrix(orientation)
            if (!matrix.isIdentity) oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val pixels = oriented.width.toDouble() * oriented.height
            val scale = min(1.0, min(MAX_EDGE.toDouble() / maxOf(oriented.width, oriented.height), sqrt(MAX_OUTPUT_PIXELS / pixels)))
            val width = (oriented.width * scale).roundToInt().coerceAtLeast(1)
            val height = (oriented.height * scale).roundToInt().coerceAtLeast(1)
            normalized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(normalized).apply {
                drawColor(Color.WHITE)
                drawBitmap(oriented, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val output = ByteArrayOutputStream()
            var quality = 90
            do {
                output.reset()
                check(normalized.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "图片处理失败" }
                quality -= 10
            } while (output.size() > MAX_IMAGE_BYTES && quality >= 60)
            require(output.size() in 1..MAX_IMAGE_BYTES) { "图片细节过多，请裁剪到日程内容后重新选择" }
            val name = "${UUID.randomUUID()}.jpg"
            val file = imageFile(name)
            try {
                file.writeBytes(output.toByteArray())
            } catch (failure: Exception) {
                file.delete()
                throw failure
            }
            name
        } finally {
            normalized?.recycle()
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
    }

    suspend fun dataUrl(name: String): String = withContext(Dispatchers.IO) {
        val file = imageFile(name)
        require(file.isFile && file.length() in 1..MAX_IMAGE_BYTES.toLong()) { "图片已不可用，请重新选择后发送" }
        "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(file.readBytes())
    }

    /** 使用 APK 内置的中文识别模型，本步骤不上传原图，也不等待运行时模型下载。 */
    suspend fun recognizeText(name: String): String = withContext(Dispatchers.IO) {
        val file = imageFile(name)
        require(file.isFile && file.length() in 1..MAX_IMAGE_BYTES.toLong()) { "图片已不可用，请重新选择后发送" }
        val input = InputImage.fromFilePath(context, Uri.fromFile(file))
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        suspendCancellableCoroutine { continuation ->
            try {
                // 取消/超时后让原生识别任务自然结束，再释放模型资源。
                recognizer.process(input).addOnCompleteListener(Executor { it.run() }) { task ->
                    try {
                        if (continuation.isActive) {
                            if (task.isSuccessful) continuation.resume(task.result.text.trim())
                            else continuation.resumeWithException(task.exception ?: IOException("本机文字识别失败"))
                        }
                    } finally {
                        recognizer.close()
                    }
                }
            } catch (failure: Exception) {
                recognizer.close()
                continuation.resumeWithException(failure)
            }
        }
    }

    suspend fun thumbnail(name: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val file = imageFile(name)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 600) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    fun delete(name: String) {
        if (FILE_NAME.matches(name)) imageFile(name).delete()
    }

    fun retainOnly(names: Set<String>) {
        directory.listFiles()?.filter { FILE_NAME.matches(it.name) && it.name !in names }?.forEach { it.delete() }
    }

    private fun imageFile(name: String): File {
        require(FILE_NAME.matches(name)) { "图片文件名无效" }
        return File(directory, name)
    }

    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        private const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
        private const val MAX_EDGE = 4096
        private const val MAX_DECODE_PIXELS = 8_000_000L
        private const val MAX_OUTPUT_PIXELS = 4_000_000.0
        private val FILE_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
    }
}
