/*
 * Copyright 2026 TrueWind contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.kf6999.truewind.core.fusion

import kotlin.math.max
import kotlin.math.pow

/** Persistable per-member error statistics. */
data class MemberStat(
    val memberId: String,
    /** Decayed Σ reliability · |e|², (m/s)². */
    val sumSquares: Double,
    /** Decayed Σ reliability (amount of evidence). */
    val weight: Double,
    val updatedMs: Long,
) {
    /** Observation-only mean-square vector error, or null without evidence. */
    val mse: Double? get() = if (weight > 1e-9) sumSquares / weight else null
}

/** One scored comparison of a member against an observation. */
data class MemberError(val memberId: String, val squaredError: Double, val weight: Double)

/**
 * Per-member exponentially weighted mean-square vector error against QC'd observations, and the
 * weights derived from it (SPEC 4.3): `w_m ∝ 1 / (ε + mse_m)`, normalised, floored.
 *
 * The EWMA is kept as decayed sums (half-life [FusionConfig.halfLifeMs]) so irregular update times
 * and batch sizes are handled exactly. Each member also carries a prior pseudo-observation of
 * weight [FusionConfig.priorPseudoWeight] at `mse0 = (ε + priorMse) / prior − ε`, which makes the
 * weights equal the registry priors when no observations exist and lets evidence take over smoothly.
 *
 * Not thread-safe; owned by the fetch cycle.
 */
class MemberWeights(private val config: FusionConfig = FusionConfig()) {
    private class Stat(var sumSquares: Double, var weight: Double, var updatedMs: Long)

    private val stats = LinkedHashMap<String, Stat>()

    val isEmpty: Boolean get() = stats.isEmpty()

    /** Adds a batch of errors observed at [nowMs]. */
    fun update(errors: List<MemberError>, nowMs: Long) {
        for ((member, group) in errors.groupBy { it.memberId }) {
            val s = stats.getOrPut(member) { Stat(0.0, 0.0, nowMs) }
            decay(s, nowMs)
            for (e in group) {
                if (!e.squaredError.isFinite() || e.weight <= 0.0) continue
                s.sumSquares += e.weight * e.squaredError
                s.weight += e.weight
            }
        }
    }

    private fun decay(s: Stat, nowMs: Long) {
        val dt = nowMs - s.updatedMs
        if (dt > 0) {
            val f = 0.5.pow(dt.toDouble() / config.halfLifeMs)
            s.sumSquares *= f
            s.weight *= f
            s.updatedMs = nowMs
        }
    }

    private fun priorMse(memberId: String): Double {
        val p = config.priors.prior(memberId).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        return max(0.0, (config.epsilon + config.priorMse) / p - config.epsilon)
    }

    /** Effective mse of [memberId] at [nowMs]: evidence blended with the prior pseudo-observation. */
    fun effectiveMse(memberId: String, nowMs: Long): Double {
        val m0 = priorMse(memberId)
        val w0 = config.priorPseudoWeight
        val s = stats[memberId] ?: return m0
        val f = if (nowMs > s.updatedMs) 0.5.pow((nowMs - s.updatedMs).toDouble() / config.halfLifeMs) else 1.0
        val w = s.weight * f
        val ss = s.sumSquares * f
        if (w + w0 <= 1e-12) return m0
        return (ss + w0 * m0) / (w + w0)
    }

    /** Normalised, floored weights for [members] (in that order) at [nowMs]. */
    fun weights(members: List<String>, nowMs: Long): DoubleArray {
        val raw = DoubleArray(members.size) { 1.0 / (config.epsilon + effectiveMse(members[it], nowMs)) }
        return normaliseWithFloor(raw, config.weightFloor)
    }

    fun weightMap(members: List<String>, nowMs: Long): Map<String, Double> {
        val w = weights(members, nowMs)
        return members.indices.associate { members[it] to w[it] }
    }

    fun export(): List<MemberStat> = stats.map { (id, s) -> MemberStat(id, s.sumSquares, s.weight, s.updatedMs) }

    /**
     * Seeds statistics from persistence (previous ride in this cell). Evidence is capped at
     * [FusionConfig.persistedWeightCap] and re-timed to [nowMs] so it acts as an informed prior;
     * entries older than [FusionConfig.persistedMaxAgeMs] are ignored. Members already tracked are kept.
     */
    fun import(persisted: List<MemberStat>, nowMs: Long) {
        for (p in persisted) {
            if (stats.containsKey(p.memberId)) continue
            if (nowMs - p.updatedMs > config.persistedMaxAgeMs) continue
            val mse = p.mse ?: continue
            if (!mse.isFinite()) continue
            val w = p.weight.coerceAtMost(config.persistedWeightCap)
            stats[p.memberId] = Stat(mse * w, w, nowMs)
        }
    }

    companion object {
        /**
         * Normalises [raw] to sum 1 with every element at least [floor]: elements that would fall
         * below the floor are pinned to it and the remaining mass is shared by the others in
         * proportion to [raw]. Equal weights when `n · floor ≥ 1`.
         */
        fun normaliseWithFloor(raw: DoubleArray, floor: Double): DoubleArray {
            val n = raw.size
            if (n == 0) return raw
            if (n * floor >= 1.0) return DoubleArray(n) { 1.0 / n }
            val pinned = BooleanArray(n)
            val out = DoubleArray(n)
            while (true) {
                var freeRaw = 0.0
                var pinnedCount = 0
                for (i in 0 until n) if (pinned[i]) pinnedCount++ else freeRaw += max(raw[i], 0.0)
                val freeMass = 1.0 - pinnedCount * floor
                var changed = false
                for (i in 0 until n) {
                    if (pinned[i]) {
                        out[i] = floor
                        continue
                    }
                    val share = if (freeRaw > 0.0) max(raw[i], 0.0) / freeRaw else 1.0 / (n - pinnedCount)
                    out[i] = freeMass * share
                    if (out[i] < floor) {
                        pinned[i] = true
                        changed = true
                    }
                }
                if (!changed) return out
            }
        }

        /** Line format: `memberId<TAB>sumSquares<TAB>weight<TAB>updatedMs`, one member per line. */
        fun encode(stats: List<MemberStat>): String =
            stats.joinToString("\n") { "${it.memberId}\t${it.sumSquares}\t${it.weight}\t${it.updatedMs}" }

        fun decode(text: String): List<MemberStat> = text.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 4) return@mapNotNull null
            val ss = p[1].toDoubleOrNull() ?: return@mapNotNull null
            val w = p[2].toDoubleOrNull() ?: return@mapNotNull null
            val t = p[3].toLongOrNull() ?: return@mapNotNull null
            if (p[0].isBlank() || !ss.isFinite() || !w.isFinite() || w < 0.0 || ss < 0.0) return@mapNotNull null
            MemberStat(p[0], ss, w, t)
        }.toList()
    }
}
