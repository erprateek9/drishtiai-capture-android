package com.drishtiai.capture.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.drishtiai.capture.model.QualityConfig
import com.drishtiai.capture.model.QualityResult
import com.drishtiai.capture.scoring.ScoringEngine
import java.io.ByteArrayOutputStream

/**
 * CameraX ImageAnalysis.Analyzer integration point. This is a placeholder
 * wiring: it decodes each YUV_420_888 frame to a Bitmap and runs it through
 * ScoringEngine on every analyzed frame. For production use, replace the
 * YUV->JPEG->Bitmap round trip with a direct YUV-to-luma conversion (skip
 * chroma planes entirely - the scoring engine only needs grayscale) to cut
 * per-frame latency; left simple here for MVP clarity.
 *
 * Usage (in the host app's CameraX setup):
 *
 *   val analyzer = QualityAnalyzer(configProvider = { sdk.getConfig() }) { result ->
 *       runOnUiThread { updateGuidanceOverlay(result) }
 *   }
 *   imageAnalysis.setAnalyzer(cameraExecutor, analyzer)
 */
class QualityAnalyzer(
    private val configProvider: () -> QualityConfig,
    private val onResult: (QualityResult) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(image)
            if (bitmap != null) {
                val result = ScoringEngine.score(bitmap, configProvider())
                onResult(result)
            }
        } finally {
            image.close()
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }
}
