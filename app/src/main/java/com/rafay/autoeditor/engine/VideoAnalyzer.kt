package com.rafay.autoeditor.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class VideoInfo(
    val durationSec: Double, val width: Int, val height: Int,
    val fps: Double, val hasAudio: Boolean, val sampleRate: Int
)

/** All analysis is local: MediaMetadataRetriever frames + simple image maths + bundled ML Kit face model. */
object VideoAnalyzer {

    fun probe(ctx: Context, uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        var dur = 0.0; var w = 0; var h = 0
        try {
            r.setDataSource(ctx, uri)
            dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("Cannot read video duration")) / 1000.0
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: error("Cannot read video size")
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: error("Cannot read video size")
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }   // FFmpeg auto-rotates, so work with displayed size
        } finally { r.release() }

        var fps = 30.0; var hasAudio = false; var sr = 48000
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                        .getOrElse { runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrDefault(30.0) }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
            }
        } finally { ex.release() }
        if (fps < 10 || fps > 120) fps = 30.0
        return VideoInfo(dur, w and 1.inv(), h and 1.inv(), fps, hasAudio, sr)
    }

    private fun frame(r: MediaMetadataRetriever, t: Double, w: Int, info: VideoInfo): Bitmap? {
        val h = (w.toDouble() * info.height / info.width).roundToInt().coerceAtLeast(8)
        return r.getScaledFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, w, h)
    }

    private fun gray(b: Bitmap): IntArray {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        return IntArray(w * h) { val c = px[it]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        var s = 0L; val n = min(a.size, b.size)
        for (i in 0 until n) s += abs(a[i] - b[i])
        return s.toDouble() / n
    }

    /**
     * Finds where the opening bold-text section ends = the first hard scene cut between 2s and 8.5s.
     * Returns seconds, or -1.0 if no confident cut was found.
     */
    fun detectOpeningEnd(r: MediaMetadataRetriever, info: VideoInfo): Double {
        val step = 0.2
        val limit = min(9.0, info.durationSec - 0.3)
        val times = ArrayList<Double>(); val diffs = ArrayList<Double>()
        var prev: IntArray? = null
        var t = 0.0
        while (t <= limit) {
            val b = frame(r, t, 96, info)
            if (b != null) {
                val g = gray(b); b.recycle()
                prev?.let { times.add(t); diffs.add(meanAbsDiff(it, g)) }
                prev = g
            }
            t += step
        }
        if (diffs.size < 5) return -1.0
        val median = diffs.sorted()[diffs.size / 2]
        var best = -1.0; var bestT = -1.0
        for (i in diffs.indices) if (times[i] in 2.0..8.5 && diffs[i] > best) { best = diffs[i]; bestT = times[i] }
        if (bestT < 0 || best < 18.0 || best < 3.0 * max(median, 1.0)) return -1.0

        // refine to ~40 ms
        var pt = bestT - step; var pg: IntArray? = null
        var refined = bestT; var rb = -1.0
        while (pt <= bestT + 0.001) {
            val b = frame(r, max(0.0, pt), 96, info)
            if (b != null) {
                val g = gray(b); b.recycle()
                pg?.let { val d = meanAbsDiff(it, g); if (d > rb) { rb = d; refined = pt } }
                pg = g
            }
            pt += 0.04
        }
        return refined
    }

    /** Finds the horizontal line separating B-roll (top) and Among Us (bottom). Returns (splitY px, confident). */
    fun detectSplit(r: MediaMetadataRetriever, info: VideoInfo): Pair<Int, Boolean> {
        val w = 160
        val n = 10
        var acc: DoubleArray? = null; var hh = 0; var used = 0
        for (k in 0 until n) {
            val t = info.durationSec * (k + 0.5) / n
            val b = frame(r, t, w, info) ?: continue
            hh = b.height
            val g = gray(b); b.recycle()
            val rows = DoubleArray(hh - 1) { y ->
                var s = 0L; for (x in 0 until w) s += abs(g[(y + 1) * w + x] - g[y * w + x]); s.toDouble() / w
            }
            if (acc == null) acc = DoubleArray(hh - 1)
            for (y in rows.indices) acc[y] += rows[y]
            used++
        }
        if (acc == null || used == 0) return (info.height / 2 and 1.inv()) to false
        val lo = (hh * 0.30).toInt(); val hi = (hh * 0.70).toInt()
        var bestY = hh / 2; var best = -1.0
        val window = ArrayList<Double>()
        for (y in lo..min(hi, acc.size - 1)) { val v = acc[y] / used; window.add(v); if (v > best) { best = v; bestY = y } }
        val median = window.sorted()[window.size / 2]
        val confident = best > 3.0 * max(median, 1.0)
        val splitPx = (((bestY + 1).toDouble() / hh) * info.height).roundToInt() and 1.inv()
        return if (confident) splitPx to true else (info.height / 2 and 1.inv()) to false
    }

    /**
     * Samples the upper section ~3x per second; a real player = a clearly visible face (bundled ML Kit model, offline).
     * Stickers/text/emoji-only moments have no real face, so they are not flipped.
     */
    fun detectPlayerIntervals(
        r: MediaMetadataRetriever, info: VideoInfo, splitY: Int, from: Double, onProgress: (Int) -> Unit
    ): List<Pair<Double, Double>> {
        val step = 0.33
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.12f).build()
        )
        val times = ArrayList<Double>(); val flags = ArrayList<Boolean>()
        try {
            var t = from
            val total = max(info.durationSec - from, 0.1)
            while (t < info.durationSec - 0.05) {
                val full = frame(r, t, 480, info)
                var has = false
                if (full != null) {
                    val ch = (full.height * splitY.toDouble() / info.height).roundToInt().coerceIn(8, full.height)
                    val top = Bitmap.createBitmap(full, 0, 0, full.width, ch)
                    val faces = Tasks.await(detector.process(InputImage.fromBitmap(top, 0)))
                    has = faces.any { it.boundingBox.width() >= top.width * 0.08 }
                    if (top !== full) top.recycle()
                    full.recycle()
                }
                times.add(t); flags.add(has)
                onProgress((((t - from) / total) * 100).toInt().coerceIn(0, 100))
                t += step
            }
        } finally { detector.close() }
        return toIntervals(times, flags, step)
    }

    fun toIntervals(times: List<Double>, flags: List<Boolean>, step: Double): List<Pair<Double, Double>> {
        val raw = ArrayList<Pair<Double, Double>>()
        for (i in times.indices) if (flags[i]) raw.add((times[i] - step / 2) to (times[i] + step / 2))
        val merged = ArrayList<Pair<Double, Double>>()
        for (seg in raw) {
            if (merged.isNotEmpty() && seg.first - merged.last().second <= 0.7)
                merged[merged.size - 1] = merged.last().first to seg.second
            else merged.add(seg)
        }
        return merged.filter { it.second - it.first >= 0.5 }
    }

    fun parseIntervals(text: String): List<Pair<Double, Double>> =
        text.split(',', ';').mapNotNull {
            val p = it.trim().split('-'); if (p.size != 2) return@mapNotNull null
            val a = p[0].trim().toDoubleOrNull(); val b = p[1].trim().toDoubleOrNull()
            if (a != null && b != null && b > a) a to b else null
        }
}
