package com.noisewatch.android.engine

import com.noisewatch.android.model.MonitorSettings

data class EvaluationResult(
    val triggered: Boolean,
    val activeHitsInWindow: Int,
    val cooldownRemainingSec: Int
)

/**
 * Evaluates threshold hits within a sliding time window and enforces cooldown between alerts.
 */
class RuleEvaluator(
    private val minHitGapMs: Long = 250L
) {
    private val hitTimestamps = ArrayDeque<Long>()
    private var lastHitMs: Long = 0L
    private var lastTriggerMs: Long = 0L

    fun reset() {
        hitTimestamps.clear()
        lastHitMs = 0L
        lastTriggerMs = 0L
    }

    fun evaluate(nowMs: Long, effectiveDb: Float, settings: MonitorSettings): EvaluationResult {
        val windowMs = settings.timeWindowSec.coerceAtLeast(1) * 1000L
        val cooldownMs = settings.cooldownSec.coerceAtLeast(0) * 1000L

        // Prune expired hits outside the sliding window
        while (hitTimestamps.isNotEmpty() && nowMs - hitTimestamps.first() > windowMs) {
            hitTimestamps.removeFirst()
        }

        val elapsedSinceTrigger = if (lastTriggerMs == 0L) Long.MAX_VALUE else nowMs - lastTriggerMs
        val inCooldown = lastTriggerMs != 0L && elapsedSinceTrigger < cooldownMs
        val cooldownRemaining = if (inCooldown) {
            ((cooldownMs - elapsedSinceTrigger + 999L) / 1000L).toInt()
        } else {
            0
        }

        // Count hit if above threshold and past per-hit debounce gap
        if (!inCooldown && effectiveDb >= settings.thresholdDb && (nowMs - lastHitMs) >= minHitGapMs) {
            hitTimestamps.addLast(nowMs)
            lastHitMs = nowMs
        }

        val requiredHits = settings.impactCount.coerceAtLeast(1)
        return if (!inCooldown && hitTimestamps.size >= requiredHits) {
            hitTimestamps.clear()
            lastTriggerMs = nowMs
            EvaluationResult(
                triggered = true,
                activeHitsInWindow = 0,
                cooldownRemainingSec = settings.cooldownSec
            )
        } else {
            EvaluationResult(
                triggered = false,
                activeHitsInWindow = hitTimestamps.size,
                cooldownRemainingSec = cooldownRemaining
            )
        }
    }
}
