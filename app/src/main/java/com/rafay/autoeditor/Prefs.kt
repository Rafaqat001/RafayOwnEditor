package com.rafay.autoeditor

import android.content.Context

enum class MirrorMode { AUTO, ALL, OFF }

/** Every editing requirement lives here with the defaults you asked for. Saved permanently. */
data class EditSettings(
    val contrast: Int = 10,                 // +10
    val saturation: Int = -5,               // -5
    val enhance: Boolean = true,            // gentle natural enhancement (light sharpening)
    val speed: Double = 1.04,               // everything after the opening second
    val keepOpeningSeconds: Double = 1.0,   // last 1.00s of the opening section, at 1.00x
    val pitchSemitones: Double = 0.0,       // 0 = original voice
    val openingOverrideSec: Double = 0.0,   // 0 = auto-detect opening length
    val splitOverridePct: Int = 0,          // 0 = auto-detect split line (percent from top)
    val mirrorMode: MirrorMode = MirrorMode.AUTO,
    val manualIntervals: String = ""        // e.g. "6.5-9.0, 12-15" (input-video seconds); overrides AI
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("editor_settings", Context.MODE_PRIVATE)
    private val d = EditSettings()

    fun load() = EditSettings(
        contrast = sp.getInt("contrast", d.contrast),
        saturation = sp.getInt("saturation", d.saturation),
        enhance = sp.getBoolean("enhance", d.enhance),
        speed = sp.getFloat("speed", d.speed.toFloat()).toDouble().let { Math.round(it * 1000) / 1000.0 },
        keepOpeningSeconds = sp.getFloat("keep", d.keepOpeningSeconds.toFloat()).toDouble(),
        pitchSemitones = sp.getFloat("pitch", d.pitchSemitones.toFloat()).toDouble(),
        openingOverrideSec = sp.getFloat("openOverride", d.openingOverrideSec.toFloat()).toDouble(),
        splitOverridePct = sp.getInt("splitPct", d.splitOverridePct),
        mirrorMode = runCatching { MirrorMode.valueOf(sp.getString("mirror", "AUTO")!!) }.getOrDefault(MirrorMode.AUTO),
        manualIntervals = sp.getString("manual", "") ?: ""
    )

    fun save(s: EditSettings) = sp.edit()
        .putInt("contrast", s.contrast).putInt("saturation", s.saturation).putBoolean("enhance", s.enhance)
        .putFloat("speed", s.speed.toFloat()).putFloat("keep", s.keepOpeningSeconds.toFloat())
        .putFloat("pitch", s.pitchSemitones.toFloat()).putFloat("openOverride", s.openingOverrideSec.toFloat())
        .putInt("splitPct", s.splitOverridePct).putString("mirror", s.mirrorMode.name)
        .putString("manual", s.manualIntervals).apply()

    fun reset() = save(EditSettings())
}
