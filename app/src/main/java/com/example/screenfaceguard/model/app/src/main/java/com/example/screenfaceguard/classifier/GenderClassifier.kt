package com.example.screenfaceguard.classifier

import android.graphics.Bitmap
import com.example.screenfaceguard.model.GenderScores

/** Replaceable gender-classification layer. Implementations must run fully on-device. */
interface GenderClassifier {
    val isReady: Boolean
    val statusMessage: String

    /** Returns probabilities for a cropped face, or null if the classifier is unavailable. */
    fun classify(face: Bitmap): GenderScores?

    fun close()
}
