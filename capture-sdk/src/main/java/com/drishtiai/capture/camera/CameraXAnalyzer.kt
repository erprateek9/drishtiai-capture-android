package com.drishtiai.capture.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.drishtiai.capture.model.FrameAnalysisResult
import com.drishtiai.capture.model.FrameHistoryEntry
import com.drishtiai.capture.model.QualityConfig
import com.drishtiai.capture.scoring.ImageUtils
import com.drishtiai.capture.scoring.ScoringEngine
import java.util.ArrayDeque

/**
 * CameraX `ImageAnalysis.Analyzer` integration point for the *live preview*
 * path.
 *
 * Deliberate behavior split vs. [com.drishtiai.capture.scoring.ScoringEngine.analyzeFrame] (Bitmap overload):
 * CameraX delivers frames in `YUV_420_888`, and the Y-plane of that format
 * IS ALREADY luma/grayscale - there is no RGB content to convert from. So
 * this analyzer reads `image.planes[0]` directly into a DoubleArray
 * (respecting `rowStride` vs `width`, since a plane's row stride can be
 * larger than its logical width due to hardware padding) and skips any
 * YUV->RGB->grayscale conversion entirely. That grayscale array is fed
 * straight into the exact same [ScoringEngine.analyzeFrame] grayscale-array
 * core used everywhere else (it doesn't care how the grayscale was
 * produced), avoiding the previous implementation's wasteful
 * YUV->NV21->JPEG->Bitmap round-trip for every analyzed frame.
 *
 * By contrast, a *captured photo* is scored via
 * `ScoringEngine.analyzeFrame(bitmap, config)`, which does perform a true
 * RGBA-weighted grayscale conversion (see [ImageUtils.toGrayscale]) so the
 * score attached to a saved photo reflects the actual final image, not a
 * live-preview approximation.
 *
 * Usage (in the host app's CameraX setup):
 *
 *   val analyzer = sdk.newCameraAnalyzer { result -> updateGuidanceOverlay(result) }
 *   imageAnalysis.setAnalyzer(cameraExecutor, analyzer)
 */
class CameraXAnalyzer(
    private val configProvider: () -> QualityConfig,
    private val onResult: (FrameAnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    /** Rolling brightness history feeding ScoringEngine's stability check. */
    private val brightnessHistory = ArrayDeque<FrameHistoryEntry>()
    private val maxHistorySize = 32

    override fun analyze(image: ImageProxy) {
        try {
            val config = configProvider()
            val gray = yPlaneToGrayscale(image) ?: return

            val meanBrightness = ImageUtils.mean(gray)
            recordHistory(meanBrightness)

            val result = ScoringEngine.analyzeFrame(
                gray = gray,
                width = image.width,
                height = image.height,
                config = config,
                frameHistory = brightnessHistory.toList()
            )
            onResult(result)
        } finally {
            image.close()
        }
    }

    private fun recordHistory(meanBrightness: Double) {
        brightnessHistory.addLast(FrameHistoryEntry(meanBrightness, System.currentTimeMillis()))
        while (brightnessHistory.size > maxHistorySize) {
            brightnessHistory.removeFirst()
        }
    }

    /**
     * Reads the Y-plane (`planes[0]`) of a `YUV_420_888` `ImageProxy`
     * directly into a `DoubleArray` sized `width * height`, without ever
     * decoding U/V chroma planes. The Y-plane's `rowStride` can exceed
     * `width` (common on many camera HALs due to memory alignment padding),
     * so each row is copied respecting that stride rather than assuming
     * tightly-packed rows.
     */
    private fun yPlaneToGrayscale(image: ImageProxy): DoubleArray? {
        if (image.format != android.graphics.ImageFormat.YUV_420_888) return null
        if (image.planes.isEmpty()) return null

        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val width = image.width
        val height = image.height

        val gray = DoubleArray(width * height)
        val rowBytes = ByteArray(rowStride)

        buffer.rewind()
        for (row in 0 until height) {
            val rowStart = row * rowStride
            buffer.position(rowStart)
            val bytesToRead = minOf(rowStride, buffer.remaining())
            buffer.get(rowBytes, 0, bytesToRead)

            val rowOffset = row * width
            if (pixelStride == 1) {
                for (col in 0 until width) {
                    gray[rowOffset + col] = (rowBytes[col].toInt() and 0xFF).toDouble()
                }
            } else {
                for (col in 0 until width) {
                    val idx = col * pixelStride
                    gray[rowOffset + col] = (rowBytes[idx].toInt() and 0xFF).toDouble()
                }
            }
        }

        return gray
    }
}
