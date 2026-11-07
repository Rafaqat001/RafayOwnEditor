package com.rafay.autoeditor

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/** Shows the permanently saved requirements. Defaults are exactly your spec; edit only if needed. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var enhance: CheckBox
    private lateinit var mirror: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        setContentView(ScrollView(this).apply { addView(root) })

        fun title(t: String) = root.addView(TextView(this).apply { text = t; textSize = 22f; setPadding(0, 0, 0, 24) })
        fun field(key: String, label: String, decimal: Boolean = true) {
            root.addView(TextView(this).apply { text = label; setPadding(0, 16, 0, 0) })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            }
            fields[key] = e; root.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        title("Saved editing settings")
        field("contrast", "Contrast (default +10)", false)
        field("saturation", "Saturation (default -5)", false)
        field("speed", "Speed after opening (default 1.04)")
        field("keep", "Seconds kept from opening, at 1.00x (default 1.0)")
        field("pitch", "Pitch semitones (0 = original voice; try -0.5 .. +0.5)")
        field("open", "Opening length override, seconds (0 = auto-detect)")
        field("split", "Split line override, % from top (0 = auto-detect)", false)

        enhance = CheckBox(this).apply { text = "Natural enhancement (light sharpening)" }; root.addView(enhance)
        root.addView(TextView(this).apply { text = "Player mirroring"; setPadding(0, 16, 0, 0) })
        mirror = Spinner(this).apply { adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, MirrorMode.values().map { it.name }) }
        root.addView(mirror)
        root.addView(TextView(this).apply { text = "Manual mirror intervals, input-video seconds, e.g. 6.5-9, 12-15 (blank = use AI)"; setPadding(0, 16, 0, 0) })
        val manual = EditText(this); fields["manual"] = manual; root.addView(manual)

        root.addView(Button(this).apply { text = "Save"; setOnClickListener { save() } })
        root.addView(Button(this).apply { text = "Reset to my defaults"; setOnClickListener { prefs.reset(); fill(); toast("Reset") } })
        fill()
    }

    private fun fill() {
        val s = prefs.load()
        fields["contrast"]!!.setText(s.contrast.toString()); fields["saturation"]!!.setText(s.saturation.toString())
        fields["speed"]!!.setText(s.speed.toString()); fields["keep"]!!.setText(s.keepOpeningSeconds.toString())
        fields["pitch"]!!.setText(s.pitchSemitones.toString()); fields["open"]!!.setText(s.openingOverrideSec.toString())
        fields["split"]!!.setText(s.splitOverridePct.toString()); fields["manual"]!!.setText(s.manualIntervals)
        enhance.isChecked = s.enhance; mirror.setSelection(s.mirrorMode.ordinal)
    }

    private fun save() {
        val d = EditSettings()
        fun dbl(k: String, def: Double) = fields[k]!!.text.toString().toDoubleOrNull() ?: def
        val speed = dbl("speed", d.speed).coerceIn(0.5, 2.0)
        prefs.save(EditSettings(
            contrast = dbl("contrast", 10.0).toInt().coerceIn(-100, 100),
            saturation = dbl("saturation", -5.0).toInt().coerceIn(-100, 100),
            enhance = enhance.isChecked, speed = speed,
            keepOpeningSeconds = dbl("keep", 1.0).coerceIn(0.2, 5.0),
            pitchSemitones = dbl("pitch", 0.0).coerceIn(-6.0, 6.0),
            openingOverrideSec = dbl("open", 0.0).coerceAtLeast(0.0),
            splitOverridePct = dbl("split", 0.0).toInt().coerceIn(0, 99),
            mirrorMode = MirrorMode.values()[mirror.selectedItemPosition],
            manualIntervals = fields["manual"]!!.text.toString()))
        toast("Saved permanently"); finish()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
