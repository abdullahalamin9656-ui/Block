package com.example.screenfaceguard.model

import android.graphics.RectF

enum class Gender { FEMALE, MALE, UNCERTAIN }

/** Raw probabilities returned by a GenderClassifier. */
data class GenderScores(val female: Float, val male: Float) {

    fun classify(settings: ProtectionSettings): Gender = when {
        female >= settings.femaleThreshold && female >= male -> Gender.FEMALE
        male >= settings.maleThreshold && male > female -> Gender.MALE
        else -> Gender.UNCERTAIN
    }
}

/** A face found in one captured frame. [rect] is in frame pixel coordinates. */
data class FaceBox(val rect: RectF, val gender: Gender)

/** FEMALE -> block, MALE -> keep visible, UNCERTAIN -> depends on the setting. */
fun Gender.shouldBlock(blockUncertain: Boolean): Boolean = when (this) {
    Gender.FEMALE -> true
    Gender.MALE -> false
    Gender.UNCERTAIN -> blockUncertain
}
