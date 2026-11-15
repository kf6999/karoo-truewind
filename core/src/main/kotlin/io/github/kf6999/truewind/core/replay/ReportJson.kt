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

package io.github.kf6999.truewind.core.replay

import java.util.Locale

/** Minimal JSON serialisation of the report maps (no JSON dependency in core). NaN/inf become null. */
object ReportJson {
    fun write(value: Any?, indent: String = "  "): String = StringBuilder().also { append(it, value, indent, 0) }.toString()

    private fun append(sb: StringBuilder, v: Any?, indent: String, level: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Double -> if (v.isFinite()) sb.append(round(v)) else sb.append("null")
            is Float -> append(sb, v.toDouble(), indent, level)
            is Number -> sb.append(v)
            is String -> string(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                v.entries.forEachIndexed { i, (k, value) ->
                    sb.append(indent.repeat(level + 1)); string(sb, k.toString()); sb.append(": ")
                    append(sb, value, indent, level + 1)
                    if (i < v.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append(indent.repeat(level)).append('}')
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                if (list.all { it == null || it is Number || it is String || it is Boolean }) {
                    sb.append('[')
                    list.forEachIndexed { i, e -> if (i > 0) sb.append(", "); append(sb, e, indent, level) }
                    sb.append(']'); return
                }
                sb.append("[\n")
                list.forEachIndexed { i, e ->
                    sb.append(indent.repeat(level + 1)); append(sb, e, indent, level + 1)
                    if (i < list.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append(indent.repeat(level)).append(']')
            }
            else -> string(sb, v.toString())
        }
    }

    private fun round(d: Double): String {
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        return String.format(Locale.ROOT, "%.6g", d).let { s -> if ('e' in s) s else s.trimEnd('0').trimEnd('.') }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format(Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    /** Flattens numeric leaves to `a.b.c -> value` (used for baseline regression checks). */
    fun flatten(v: Any?, prefix: String = "", out: MutableMap<String, Double> = linkedMapOf()): Map<String, Double> {
        when (v) {
            is Map<*, *> -> v.forEach { (k, x) -> flatten(x, if (prefix.isEmpty()) k.toString() else "$prefix.$k", out) }
            is Number -> v.toDouble().takeIf { it.isFinite() }?.let { out[prefix] = it }
            else -> {}
        }
        return out
    }
}
