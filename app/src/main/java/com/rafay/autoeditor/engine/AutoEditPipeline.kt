package com.rafay.autoeditor.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.rafay.autoeditor.EditSettings
import com.rafay.autoeditor.MirrorMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

class AutoEditPipeline(private val ctx: Context) {

    suspend fun run(
        uri: Uri, s: EditSettings,
        stage: (String) -> Unit, progress: (Int) -> Unit, log: (String) -> Unit
    ): File = withContext(Dispatchers.Default) {
        stage("Step 1/11: Analyzing video")
        val info = VideoAnalyzer.probe(ctx, uri)
        require(info.durationSec <= 181.0) { "Video is longer than 3 minutes" }
        require(info.durationSec > 2.0) { "Video is too short" }
        log("Input: ${info.width}x${info.height}, ${"%.2f".format(info.fps)} fps, ${"%.1f".format(info.durationSec)} s, audio=${info.hasAudio}")

        val outDir = File(ctx.cacheDir, "out").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }          // never keep old temp files
        require(outDir.usableSpace > 300L * 1024 * 1024) { "Not enough free storage (need ~300 MB)" }
        val out = File(outDir, "edited_${System.currentTimeMillis()}.mp4")

        val retr = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
        val plan: Plan
        try {
            stage("Step 2/11: Detecting opening text section")
            val cut: Double = if (s.openingOverrideSec > 0) {
                log("Opening length: manual ${s.openingOverrideSec}s"); s.openingOverrideSec
            } else {
                val d = VideoAnalyzer.detectOpeningEnd(retr, info)
                if (d > 0) { log("Opening ends at ${"%.2f".format(d)}s (auto)"); d }
                else { log("WARNING: opening not detected - assumed 5.0s. Set the opening length in Settings and re-run."); 5.0 }
            }
            val t1 = min(cut, info.durationSec - 0.5)
            stage("Step 3/11: Keeping last ${s.keepOpeningSeconds}s of opening")
            val t0 = max(0.0, t1 - s.keepOpeningSeconds)
            log("Keeping ${"%.2f".format(t0)}s - ${"%.2f".format(t1)}s at 1.00x")

            stage("Step 5/11: Detecting split-screen layout")
            val splitY = if (s.splitOverridePct in 1..99) (info.height * s.splitOverridePct / 100) and 1.inv()
            else VideoAnalyzer.detectSplit(retr, info).let { (y, ok) ->
                log(if (ok) "Split line at y=$y of ${info.height}" else "WARNING: split not confident - using 50% (y=$y). Adjust in Settings if wrong.")
                y
            }

            stage("Step 6/11: Finding player footage")
            val intervals: List<Pair<Double, Double>> = when {
                s.mirrorMode == MirrorMode.OFF -> emptyList()
                s.mirrorMode == MirrorMode.ALL -> listOf(t1 to info.durationSec)
                s.manualIntervals.isNotBlank() -> VideoAnalyzer.parseIntervals(s.manualIntervals)
                else -> VideoAnalyzer.detectPlayerIntervals(retr, info, splitY, t1) { progress(it * 30 / 100) }
            }.map { max(it.first, t1) to min(it.second, info.durationSec) }.filter { it.second > it.first }
            log("Mirror intervals: " + if (intervals.isEmpty()) "none" else intervals.joinToString { "%.1f-%.1f".format(it.first, it.second) })
            plan = Plan(info.width, info.height, info.fps, info.hasAudio, info.sampleRate, t0, t1, splitY, intervals, s)
        } finally { retr.release() }

        stage("Steps 4,7-10/11: Color, mirror, speed, audio, export")
        val graph = FilterGraphBuilder.build(plan)
        val input = FFmpegKitConfig.getSafParameterForRead(ctx, uri)
        val expected = (plan.openEnd - plan.openStart) + (info.durationSec - plan.openEnd) / s.speed
        val onP = { pct: Int -> progress(30 + pct * 70 / 100) }

        var res = exec(args(input, graph, plan, out, true), expected, onP)
        if (!res.first) {
            log("libx264 unavailable/failed, retrying with hardware H.264 encoder")
            out.delete()
            res = exec(args(input, graph, plan, out, false), expected, onP)
        }
        if (!res.first) { out.delete(); error("FFmpeg failed: " + res.second.takeLast(600)) }
        progress(100)
        out
    }

    private fun args(input: String, graph: String, p: Plan, out: File, x264: Boolean): Array<String> {
        val a = ArrayList<String>()
        a += listOf("-y", "-i", input, "-filter_complex", graph, "-map", "[v]")
        if (p.hasAudio) a += listOf("-map", "[a]")
        if (x264) a += listOf("-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p")
        else {
            val br = (p.width.toLong() * p.height * p.fps * 0.12).toLong().coerceIn(6_000_000L, 25_000_000L)
            a += listOf("-c:v", "h264_mediacodec", "-b:v", br.toString(), "-pix_fmt", "yuv420p")
        }
        if (p.hasAudio) a += listOf("-c:a", "aac", "-b:a", "192k", "-ar", p.sampleRate.toString())
        a += listOf("-max_muxing_queue_size", "1024", "-movflags", "+faststart", out.absolutePath)
        return a.toTypedArray()
    }

    private suspend fun exec(args: Array<String>, expected: Double, progress: (Int) -> Unit): Pair<Boolean, String> =
        suspendCancellableCoroutine { cont ->
            val tail = StringBuilder()
            val session = FFmpegKit.executeWithArgumentsAsync(args,
                { sess -> if (cont.isActive) cont.resume(ReturnCode.isSuccess(sess.returnCode) to synchronized(tail) { tail.toString() }) },
                { l -> synchronized(tail) { tail.append(l.message); if (tail.length > 4000) tail.delete(0, tail.length - 4000) } },
                { st -> if (expected > 0) progress(((st.time / 1000.0) / expected * 100).toInt().coerceIn(0, 99)) })
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
}
