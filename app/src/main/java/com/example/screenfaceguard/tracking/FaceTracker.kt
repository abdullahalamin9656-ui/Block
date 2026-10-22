package com.example.screenfaceguard.tracking

import android.graphics.RectF
import com.example.screenfaceguard.model.Gender
import com.example.screenfaceguard.model.shouldBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Tracks faces across frames in frame coordinates.
 * - smooths box movement
 * - classifies a track only occasionally (not every frame)
 * - keeps covers briefly after the face disappears
 *
 * Covered faces are sticky: once our own black cover hides a face, the
 * detector usually cannot see it any more, so a blocked track that was
 * seen at least twice is held STICKY_FACTOR times longer than holdMillis.
 */
class FaceTracker {

    private companion object {
        const val MATCH_OVERLAP = 0.25f      // overlap / smaller area
        const val SMOOTHING = 0.6f           // 1.0 = no smoothing
        const val RECLASSIFY_MS = 1000L
        const val STICKY_FACTOR = 5
        const val UNBLOCK_VOTES = 2          // consecutive "not blocked" results needed
        const val PARTIAL_AREA_RATIO = 0.6f  // smaller detection = partly hidden face
    }

    private class Track(var rect: RectF, var gender: Gender, now: Long) {
        var lastSeen = now
        var lastClassified = now
        var hits = 1
        var unblockVotes = 0
    }

    private val tracks = ArrayList<Track>()

    /**
     * @param classify called only when a track needs a (new) gender decision
     * @return frame-coordinate boxes that must be covered
     */
    fun update(
        detections: List<RectF>,
        now: Long,
        holdMillis: Long,
        blockUncertain: Boolean,
        classify: (RectF) -> Gender?
    ): List<RectF> {
        val oldCount = tracks.size
        val seen = BooleanArray(oldCount)

        for (det in detections) {
            var best = -1
            var bestScore = MATCH_OVERLAP
            for (i in 0 until oldCount) {
                if (seen[i]) continue
                val score = overlap(tracks[i].rect, det)
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            if (best >= 0) {
                seen[best] = true
                refresh(tracks[best], det, now, blockUncertain, classify)
            } else {
                val g = classify(det) ?: Gender.UNCERTAIN
                tracks.add(Track(RectF(det), g, now))
            }
        }

        for (i in oldCount - 1 downTo 0) {
            if (seen[i]) continue
            val t = tracks[i]
            val hold = if (t.hits >= 2) holdMillis * STICKY_FACTOR else holdMillis
            if (now - t.lastSeen > hold) tracks.removeAt(i)
        }

        val out = ArrayList<RectF>(tracks.size)
        for (t in tracks) {
            if (t.gender.shouldBlock(blockUncertain)) out.add(RectF(t.rect))
        }
        return out
    }

    fun clear() = tracks.clear()

    private fun refresh(
        t: Track,
        det: RectF,
        now: Long,
        blockUncertain: Boolean,
        classify: (RectF) -> Gender?
    ) {
        val blocked = t.gender.shouldBlock(blockUncertain)
        val full = det.width() * det.height() >= PARTIAL_AREA_RATIO * t.rect.width() * t.rect.height()

        if (full) {
            t.rect.set(
                lerp(t.rect.left, det.left),
                lerp(t.rect.top, det.top),
                lerp(t.rect.right, det.right),
                lerp(t.rect.bottom, det.bottom)
            )
        } else if (blocked) {
            // Only a piece of the face peeks out from the cover: grow, never shrink.
            t.rect.union(det)
        } else {
            t.rect.set(det)
        }
        t.hits++
        t.lastSeen = now

        // Do not re-classify a partly hidden face: a partial crop is unreliable.
        if (now - t.lastClassified >= RECLASSIFY_MS && (full || !blocked)) {
            t.lastClassified = now
            val g = classify(det)
            if (g != null) applyGender(t, g, blockUncertain)
        }
    }

    // Fail-safe: a blocked face is revealed only after repeated "not blocked" results.
    private fun applyGender(t: Track, g: Gender, blockUncertain: Boolean) {
        val wasBlocked = t.gender.shouldBlock(blockUncertain)
        val nowBlocked = g.shouldBlock(blockUncertain)
        if (wasBlocked && !nowBlocked) {
            t.unblockVotes++
            if (t.unblockVotes >= UNBLOCK_VOTES) {
                t.gender = g
                t.unblockVotes = 0
            }
        } else {
            t.gender = g
            t.unblockVotes = 0
        }
    }

    private fun lerp(old: Float, new: Float) = old + (new - old) * SMOOTHING

    private fun overlap(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left)
        val tp = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bt = min(a.bottom, b.bottom)
        if (r <= l || bt <= tp) return 0f
        val inter = (r - l) * (bt - tp)
        val minArea = min(a.width() * a.height(), b.width() * b.height())
        return if (minArea <= 0f) 0f else inter / minArea
    }
}
