package com.example.screenfaceguard.detection

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Wraps ML Kit face detection (runs on-device).
 * [detect] blocks, so call it from a background thread only.
 */
class FaceDetectorManager {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.05f)
            .build()
    )

    private val directExecutor = Executor { it.run() }

    /** Returns face bounding boxes in the bitmap's pixel coordinates. */
    fun detect(frame: Bitmap): List<RectF> {
        val latch = CountDownLatch(1)
        var result: List<RectF> = emptyList()
        try {
            detector.process(InputImage.fromBitmap(frame, 0))
                .addOnSuccessListener(directExecutor) { faces ->
                    result = faces.map { RectF(it.boundingBox) }
                    latch.countDown()
                }
                .addOnFailureListener(directExecutor) { e ->
                    Log.w("FaceDetector", "Detection failed", e)
                    latch.countDown()
                }
            latch.await(1500, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w("FaceDetector", "Detector error", e)
        }
        return result
    }

    fun close() {
        try {
            detector.close()
        } catch (_: Exception) {
        }
    }
}
