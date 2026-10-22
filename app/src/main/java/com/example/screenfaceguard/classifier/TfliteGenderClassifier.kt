package com.example.screenfaceguard.classifier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.example.screenfaceguard.model.GenderScores
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp

/**
 * Runs assets/gender_model.tflite (the only external asset).
 *
 * Expected model: input NHWC [1,H,W,3] (float32 or uint8),
 * output [1,N] float32/uint8 (N=1 sigmoid, or N>=2 probabilities/logits).
 * assets/labels.txt: one label per output index, e.g.
 *     female
 *     male
 * For a single-output model, the one label says what the output means.
 * If the file or model is missing, the classifier stays "not ready" and
 * returns null. It never invents results.
 */
class TfliteGenderClassifier(private val context: Context) : GenderClassifier {

    companion object {
        private const val TAG = "TfliteGender"
        const val MODEL_FILE = "gender_model.tflite"
        const val LABELS_FILE = "labels.txt"

        // Float models: (pixel - MEAN) / STD. Default maps 0..255 to 0..1.
        // For a model trained on -1..1 use MEAN = 127.5f, STD = 127.5f.
        private const val INPUT_MEAN = 0f
        private const val INPUT_STD = 255f

        private val FEMALE_NAMES = setOf("female", "woman", "women", "f")
        private val MALE_NAMES = setOf("male", "man", "men", "m")
    }

    private var interpreter: Interpreter? = null
    private var inputW = 0
    private var inputH = 0
    private var inputFloat = true
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var scaledBitmap: Bitmap
    private lateinit var scaledCanvas: Canvas
    private lateinit var pixels: IntArray
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    private var outputCount = 0
    private var outputFloat = true
    private var outScale = 1f
    private var outZero = 0
    private lateinit var outFloat: Array<FloatArray>
    private lateinit var outByte: Array<ByteArray>

    private var femaleIdx = -1
    private var maleIdx = -1
    private var singleOutputIsFemale = true

    override var statusMessage: String = "Not initialised"
        private set

    override val isReady: Boolean
        get() = interpreter != null

    init {
        initialise()
    }

    private fun initialise() {
        try {
            val labels = try {
                context.assets.open(LABELS_FILE).bufferedReader().useLines { seq ->
                    seq.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toList()
                }
            } catch (e: Exception) {
                statusMessage = "Missing assets/$LABELS_FILE"
                return
            }
            femaleIdx = labels.indexOfFirst { it in FEMALE_NAMES }
            maleIdx = labels.indexOfFirst { it in MALE_NAMES }
            if (femaleIdx < 0 && maleIdx < 0) {
                statusMessage = "$LABELS_FILE must contain 'female' and/or 'male'"
                return
            }

            val modelBuffer = try {
                loadModelFile()
            } catch (e: Exception) {
                statusMessage = "Missing assets/$MODEL_FILE"
                return
            }

            val options = Interpreter.Options().apply { setNumThreads(2) }
            val interp = Interpreter(modelBuffer, options)

            val inTensor = interp.getInputTensor(0)
            val inShape = inTensor.shape()
            if (inShape.size != 4 || inShape[3] != 3) {
                statusMessage = "Model input must be NHWC [1,H,W,3]"
                interp.close()
                return
            }
            inputH = inShape[1]
            inputW = inShape[2]
            inputFloat = when (inTensor.dataType()) {
                DataType.FLOAT32 -> true
                DataType.UINT8 -> false
                else -> {
                    statusMessage = "Unsupported input type ${inTensor.dataType()}"
                    interp.close()
                    return
                }
            }

            val outTensor = interp.getOutputTensor(0)
            outputCount = outTensor.shape().last()
            outputFloat = when (outTensor.dataType()) {
                DataType.FLOAT32 -> true
                DataType.UINT8 -> false
                else -> {
                    statusMessage = "Unsupported output type ${outTensor.dataType()}"
                    interp.close()
                    return
                }
            }
            if (!outputFloat) {
                val q = outTensor.quantizationParams()
                outScale = q.scale
                outZero = q.zeroPoint
            }

            if (outputCount == 1) {
                singleOutputIsFemale = femaleIdx >= 0
            } else {
                if (femaleIdx < 0 || maleIdx < 0 || femaleIdx >= outputCount || maleIdx >= outputCount) {
                    statusMessage = "labels.txt must list both female and male for a $outputCount-output model"
                    interp.close()
                    return
                }
            }

            val bytesPerChannel = if (inputFloat) 4 else 1
            inputBuffer = ByteBuffer.allocateDirect(inputW * inputH * 3 * bytesPerChannel)
                .order(ByteOrder.nativeOrder())
            scaledBitmap = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
            scaledCanvas = Canvas(scaledBitmap)
            pixels = IntArray(inputW * inputH)
            dstRect.set(0, 0, inputW, inputH)
            outFloat = Array(1) { FloatArray(outputCount) }
            outByte = Array(1) { ByteArray(outputCount) }

            interpreter = interp
            statusMessage = "Ready"
        } catch (t: Throwable) {
            Log.e(TAG, "Classifier init failed", t)
            statusMessage = "Classifier init failed: ${t.message}"
            interpreter = null
        }
    }

    private fun loadModelFile(): ByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        return fd.use { afd ->
            FileInputStream(afd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    @Synchronized
    override fun classify(face: Bitmap): GenderScores? {
        val interp = interpreter ?: return null
        return try {
            srcRect.set(0, 0, face.width, face.height)
            scaledCanvas.drawBitmap(face, srcRect, dstRect, paint)
            scaledBitmap.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)

            inputBuffer.rewind()
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (inputFloat) {
                    inputBuffer.putFloat((r - INPUT_MEAN) / INPUT_STD)
                    inputBuffer.putFloat((g - INPUT_MEAN) / INPUT_STD)
                    inputBuffer.putFloat((b - INPUT_MEAN) / INPUT_STD)
                } else {
                    inputBuffer.put(r.toByte())
                    inputBuffer.put(g.toByte())
                    inputBuffer.put(b.toByte())
                }
            }
            inputBuffer.rewind()

            val raw = FloatArray(outputCount)
            if (outputFloat) {
                interp.run(inputBuffer, outFloat)
                System.arraycopy(outFloat[0], 0, raw, 0, outputCount)
            } else {
                interp.run(inputBuffer, outByte)
                for (i in 0 until outputCount) {
                    raw[i] = ((outByte[0][i].toInt() and 0xFF) - outZero) * outScale
                }
            }
            toScores(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Classification failed", e)
            null
        }
    }

    private fun toScores(v: FloatArray): GenderScores {
        if (outputCount == 1) {
            val p = if (v[0] in 0f..1f) v[0] else 1f / (1f + exp(-v[0]))
            return if (singleOutputIsFemale) GenderScores(p, 1f - p) else GenderScores(1f - p, p)
        }
        var probs = v
        val sum = v.sum()
        if (v.any { it < 0f || it > 1f } || abs(sum - 1f) > 0.05f) {
            val max = v.max()
            val exps = FloatArray(v.size) { exp(v[it] - max) }
            val total = exps.sum()
            probs = FloatArray(v.size) { exps[it] / total }
        }
        return GenderScores(probs[femaleIdx], probs[maleIdx])
    }

    @Synchronized
    override fun close() {
        interpreter?.close()
        interpreter = null
    }
}
