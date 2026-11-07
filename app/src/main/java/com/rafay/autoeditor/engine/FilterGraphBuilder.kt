package com.rafay.autoeditor.engine

import com.rafay.autoeditor.EditSettings
import java.util.Locale
import kotlin.math.pow

/** Everything the FFmpeg graph needs. Times are seconds on the INPUT timeline. */
data class Plan(
    val width: Int,
    val height: Int,
    val fps: Double,
    val hasAudio: Boolean,
    val sampleRate: Int,
    val openStart: Double,          // start of the retained opening second
    val openEnd: Double,            // end of opening section (= start of the rest)
    val splitY: Int,                // pixel row where upper B-roll ends / Among Us begins
    val mirrorIntervals: List<Pair<Double, Double>>, // input-timeline intervals to mirror (all >= openEnd)
    val settings: EditSettings
)

object FilterGraphBuilder {
    private fun f(d: Double) = String.format(Locale.US, "%.3f", d)

    fun build(p: Plan): String {
        val s = p.settings
        val contrast = 1.0 + s.contrast / 100.0     // +10 -> 1.100
        val sat = 1.0 + s.saturation / 100.0        // -5  -> 0.950
        val fpsStr = String.format(Locale.US, "%.3f", p.fps)

        // LGPL-safe colour math with lutyuv: contrast pivots on mid-grey, saturation scales chroma around 128.
        val color = "lutyuv=y='clip((val-128)*${f(contrast)}+128,16,235)'" +
                ":u='clip((val-128)*${f(sat)}+128,16,240)':v='clip((val-128)*${f(sat)}+128,16,240)'"
        val enhance = if (s.enhance) ",unsharp=5:5:0.4:5:5:0.0" else ""

        val g = StringBuilder()
        g.append("[0:v]format=yuv420p,split=2[va][vb];")
        // 1) opening: only the final second, untouched speed
        g.append("[va]trim=start=${f(p.openStart)}:end=${f(p.openEnd)},setpts=PTS-STARTPTS,fps=$fpsStr,setsar=1[v1];")
        // 2) the rest: colour + selective mirror + speed
        g.append("[vb]trim=start=${f(p.openEnd)},setpts=PTS-STARTPTS,$color$enhance[g];")

        val rel = p.mirrorIntervals.map { (a, b) -> (a - p.openEnd).coerceAtLeast(0.0) to (b - p.openEnd) }
            .filter { it.second > it.first }
        val last = if (rel.isNotEmpty() && p.splitY > 1) {
            val en = rel.joinToString("+") { "between(t,${f(it.first)},${f(it.second)})" }
            g.append("[g]split=2[gb][gt];")
            g.append("[gt]crop=${p.width}:${p.splitY}:0:0,hflip[gf];")   // only the UPPER section is ever flipped
            g.append("[gb][gf]overlay=0:0:enable='$en'[m];")
            "m"
        } else "g"
        g.append("[$last]setpts=PTS/${f(s.speed)},fps=$fpsStr,setsar=1[v2];")

        if (p.hasAudio) {
            g.append("[0:a]asplit=2[aa][ab];")
            g.append("[aa]atrim=start=${f(p.openStart)}:end=${f(p.openEnd)},asetpts=PTS-STARTPTS[a1];")
            val pitch = if (s.pitchSemitones != 0.0) {
                val r = 2.0.pow(s.pitchSemitones / 12.0)
                // change pitch, keep duration: resample trick + tempo compensation
                "asetrate=${(p.sampleRate * r).toInt()},aresample=${p.sampleRate},atempo=${String.format(Locale.US, "%.5f", 1.0 / r)},"
            } else ""
            g.append("[ab]atrim=start=${f(p.openEnd)},asetpts=PTS-STARTPTS,${pitch}atempo=${f(s.speed)}[a2];")
            g.append("[v1][a1][v2][a2]concat=n=2:v=1:a=1[v][a]")
        } else {
            g.append("[v1][v2]concat=n=2:v=1:a=0[v]")
        }
        return g.toString()
    }
}
