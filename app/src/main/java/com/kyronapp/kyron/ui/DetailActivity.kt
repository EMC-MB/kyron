package com.kyronapp.kyron.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kyronapp.kyron.KyronApplication
import com.kyronapp.kyron.R
import com.kyronapp.kyron.data.Place
import com.kyronapp.kyron.data.WhySummary
import com.kyronapp.kyron.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLACE_ID = "place_id"
    }

    private lateinit var app: KyronApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var placeId: String? = null
    private var inspIdx: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as KyronApplication
        setContentView(R.layout.activity_detail)
        placeId = intent?.getStringExtra(EXTRA_PLACE_ID)
        findViewById<View>(R.id.d_back).setOnClickListener { finish() }
        // Shared-element grow: the list placard flies here when the
        // caller passed one; rendering starts it regardless.
        postponeEnterTransition()
        scope.launch {
            app.repository.load()
            render()
            startPostponedEnterTransition()
        }
    }

    private fun currentPlace(): Place? = placeId?.let { app.repository.byId(it) }

    private fun render() {
        val place = currentPlace() ?: run {
            findViewById<TextView>(R.id.d_name).text = "Place not found"
            // No dead buttons: hide actions that have nothing to act on.
            findViewById<View>(R.id.d_fav).visibility = View.GONE
            findViewById<View>(R.id.d_directions).visibility = View.GONE
            findViewById<View>(R.id.d_share).visibility = View.GONE
            return
        }
        // The place's own region model — a saved favorite can outlive a
        // region switch, so tone comes from the place's jurisdiction.
        val states = com.kyronapp.kyron.data.NationalCoverage.cached()
            ?: runCatching {
                com.kyronapp.kyron.data.NationalCoverage.load(this)
            }.getOrDefault(emptyList())
        val model = com.kyronapp.kyron.data.NationalCoverage
            .byRegionId(states, place.regionId)?.resultModel
            ?: com.kyronapp.kyron.data.Regions.nationalModel(app.regions.activeInfo())
        val scoreCol = Ui.scoreColor(this, place, model)

        val scoreView = findViewById<TextView>(R.id.d_score)
        val label = findViewById<TextView>(R.id.d_score_label)
        val band = findViewById<View>(R.id.d_band)
        band.setBackgroundColor(scoreCol)
        scoreView.setTextColor(scoreCol)
        if (place.hasNumericScore) {
            label.text = "SCORE"
            scoreView.textSize = 54f
            if (Ui.reduceMotion(this)) {
                scoreView.text = place.score.toString()
            } else {
                android.animation.ValueAnimator.ofInt(0, place.score).apply {
                    duration = 650
                    interpolator = android.view.animation.DecelerateInterpolator(2f)
                    addUpdateListener { scoreView.text = it.animatedValue.toString() }
                    start()
                }
            }
        } else {
            // Disposition regions: badge = the native result, compacted,
            // tone-colored. The verbatim result is in the meta line.
            label.text = "RESULT"
            scoreView.text = place.badgeText
            scoreView.textSize = 26f
        }
        bindMeter(place, model, scoreCol)

        findViewById<TextView>(R.id.d_name).text = place.name
        findViewById<TextView>(R.id.d_addr).text = Ui.titleCase(place.fullAddress())
        findViewById<TextView>(R.id.d_meta).text = if (place.hasNumericScore) {
            "${place.bandLabel} · last inspected ${Ui.fmtDate(place.date)}"
        } else {
            buildString {
                append(place.nativeResult.ifBlank { "No inspection on file" })
                if (place.date.isNotBlank()) append(" · last inspected ${Ui.fmtDate(place.date)}")
            }
        }
        findViewById<TextView>(R.id.d_source).text =
            "Source: ${SourceRegistry.byId(place.source)?.displayName ?: com.kyronapp.kyron.data.Regions.sourceDisplayName(place.source)}"

        val isFav = app.prefs.favorites.contains(place.id)
        val favBtn = findViewById<android.widget.ImageButton>(R.id.d_fav)
        favBtn.setImageResource(if (isFav) R.drawable.ic_star else R.drawable.ic_star_border)
        favBtn.setColorFilter(
            ContextCompat.getColor(this, if (isFav) R.color.star_on else R.color.white)
        )
        favBtn.contentDescription = getString(
            if (isFav) R.string.cd_watching else R.string.cd_watch)
        favBtn.setOnClickListener {
            Ui.haptic(it)
            it.animate().scaleX(0.7f).scaleY(0.7f).setDuration(90).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(160)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
            }.start()
            app.prefs.toggleFavorite(place.id)
            render()
        }
        findViewById<Button>(R.id.d_watch).text =
            if (isFav) "Watching ✓" else "Watch this place"
        findViewById<Button>(R.id.d_watch).setOnClickListener {
            app.prefs.toggleFavorite(place.id)
            render()
        }
        findViewById<Button>(R.id.d_directions).setOnClickListener {
            val q = Uri.encode(place.fullAddress())
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/search/?api=1&query=$q")
            )
            runCatching { startActivity(geoIntent) }
                .recoverCatching { startActivity(webIntent) }
                .onFailure {
                    android.widget.Toast.makeText(
                        this,
                        "No maps app available — the address is ${place.fullAddress()}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
        }
        findViewById<android.widget.ImageButton>(R.id.d_share).setOnClickListener {
            val resultText = if (place.hasNumericScore) {
                "scored ${place.score} (${place.bandLabel})"
            } else if (place.nativeResult.isNotBlank()) {
                "got the result \"${place.nativeResult}\""
            } else {
                "has no inspection result on file"
            }
            val shareText = "${place.name} $resultText" +
                (if (place.date.isNotBlank()) " — inspected ${place.date}." else ".") +
                " ${place.fullAddress()}. Tracked with Kyron."
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            runCatching { startActivity(Intent.createChooser(send, "Share this place")) }
        }
        findViewById<TextView>(R.id.d_watch_note).text = when {
            isFav && app.prefs.alertsForFavorites ->
                if (place.hasNumericScore) "You'll be alerted when this place gets a new score."
                else "You'll be alerted when this place gets a new inspection result."
            isFav -> "Favorite (favorite alerts are off in Alerts settings)."
            else -> ""
        }

        val insps = place.inspections
        val histCard = findViewById<View>(R.id.d_history_card)
        val histLine = findViewById<TextView>(R.id.d_history_line)
        val chips = findViewById<LinearLayout>(R.id.d_history_chips)
        if (insps.size > 1) {
            histCard.visibility = View.VISIBLE
            findViewById<View>(R.id.d_history_title).visibility = View.VISIBLE
            histLine.visibility = View.VISIBLE
            findViewById<View>(R.id.d_history_scroll).visibility = View.VISIBLE
            histLine.text = insps.joinToString(" → ") { it.badgeText } + "  (newest first)"
            chips.removeAllViews()
            insps.forEachIndexed { i, ins ->
                // Classic single-select history picker: toggle buttons
                // whose checked state render() owns (a tap re-renders,
                // which re-checks exactly the selected inspection).
                val chip = ToggleButton(this).apply {
                    minHeight = (40f * resources.displayMetrics.density).toInt()
                    minWidth = 0
                    val label = "${ins.badgeText} · ${Ui.fmtDate(ins.date)}"
                    textOn = label
                    textOff = label
                    text = label
                    textSize = 13f
                    isChecked = i == inspIdx
                    setBackgroundResource(R.drawable.chip_toggle)
                    setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text))
                    setPadding((14 * resources.displayMetrics.density).toInt(), 0,
                        (14 * resources.displayMetrics.density).toInt(), 0)
                    setOnClickListener { inspIdx = i; render() }
                }
                chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                })
            }
        } else {
            histCard.visibility = View.GONE
        }

        val cur = insps.getOrNull(minOf(inspIdx, maxOf(insps.size - 1, 0)))
        val whyTitle = findViewById<TextView>(R.id.d_why_title)
        val whyText = findViewById<TextView>(R.id.d_why)
        val violCard = findViewById<View>(R.id.d_viol_card)
        val violBox = findViewById<LinearLayout>(R.id.d_viol_box)
        val loadBtn = findViewById<Button>(R.id.d_load)
        val loadMsg = findViewById<TextView>(R.id.d_load_msg)

        // Score-only sources (ADPH, MCHD) never publish violations, so an empty
        // list there means "not published", never "clean inspection".
        val sourceHasViolations = SourceRegistry.byId(place.source)?.hasViolations == true
        if (!place.hasNumericScore) {
            // LF-4 disposition rendering: Florida-style jurisdictions publish
            // an outcome, not a score. Show the native result verbatim, its
            // recorded violation categories (no points exist — none shown),
            // and a link to the official record. No score is ever derived.
            whyTitle.text = "Latest inspection result"
            whyText.text = WhySummary.dispositionExplainer(place, cur)
            violCard.visibility =
                if (cur != null && cur.violations.isNotEmpty()) View.VISIBLE else View.GONE
            violBox.removeAllViews()
            if (cur != null) {
                for (v in cur.violations) {
                    violBox.addView(TextView(this).apply {
                        text = "• ${v.ruleText}"
                        textSize = 13.5f
                        setPadding(0, 10, 0, 10)
                    })
                }
            }
            if (place.detailUrl != null) {
                loadBtn.visibility = View.VISIBLE
                loadBtn.text = "View official record"
                loadBtn.setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(place.detailUrl)))
                    }.onFailure {
                        loadMsg.text = "Couldn't open the official record link."
                    }
                }
            } else {
                loadBtn.visibility = View.GONE
            }
        } else if (cur != null) {
            whyTitle.text = "Why a ${cur.score}?"
            whyText.text = when {
                cur.violations.isNotEmpty() -> WhySummary.summarize(cur)
                sourceHasViolations -> WhySummary.summarize(cur)
                else -> WhySummary.bandExplainer(cur.score, place.state) +
                    "\n\n${SourceRegistry.byId(place.source)?.displayName ?: "This source"} publishes scores and dates only — the violation detail stays on the paper report posted inside the restaurant, so no item-by-item breakdown is available online."
            }
            violCard.visibility = if (sourceHasViolations) View.VISIBLE else View.GONE
            loadBtn.visibility = View.GONE
            violBox.removeAllViews()
            if (cur.violations.isEmpty() && sourceHasViolations) {
                violBox.addView(TextView(this).apply {
                    text = "No violations recorded on this inspection."
                    setTextColor(0xFF6B7885.toInt())
                })
            }
            for (v in cur.violations) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 14, 0, 14)
                }
                val pts = TextView(this).apply {
                    text = "−${v.points}"
                    setBackgroundResource(R.drawable.pts_bg)
                    setTextColor(0xFFA33A3A.toInt())
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(14, 6, 14, 6)
                }
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 0, 0, 0)
                }
                col.addView(TextView(this).apply { text = v.ruleText; textSize = 13.5f })
                if (v.critical) {
                    col.addView(TextView(this).apply {
                        text = "CRITICAL"
                        setTextColor(0xFFA33A3A.toInt())
                        textSize = 11f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                }
                if (v.inspectorNote.isNotBlank()) {
                    col.addView(TextView(this).apply {
                        text = "Inspector: “${v.inspectorNote}”"
                        setTextColor(0xFF6B7885.toInt())
                        textSize = 12.5f
                    })
                }
                row.addView(pts)
                row.addView(col)
                violBox.addView(row)
            }
        } else {
            whyTitle.text = "Why a ${place.score}?"
            whyText.text = WhySummary.bandExplainer(place.score, place.state)
            violCard.visibility = View.GONE
            if (place.detailUrl != null) {
                loadBtn.visibility = View.VISIBLE
                loadBtn.setOnClickListener {
                    loadBtn.isEnabled = false
                    loadBtn.text = "Loading inspection details…"
                    loadMsg.text = ""
                    scope.launch {
                        runCatching { SourceRegistry.jcdh.fetchDetail(place) }
                            .onSuccess { fetched ->
                                if (fetched.isNotEmpty()) {
                                    app.repository.updatePlace(place.copy(inspections = fetched))
                                    inspIdx = 0
                                    render()
                                } else {
                                    loadMsg.text = "The county page didn't include details this time — try again later."
                                }
                            }
                            .onFailure {
                                loadMsg.text = "Couldn't load details (${it.message ?: "network error"}). The county site may be busy."
                            }
                        loadBtn.isEnabled = true
                        loadBtn.text = "Load full inspection details"
                    }
                }
            } else {
                loadBtn.visibility = View.GONE
            }
        }
    }

    /** Score meter on the placard — only meaningful on a 0–100 scale,
     *  where 85 is the published reinspection line. Other models hide it. */
    private fun bindMeter(place: Place, model: String, scoreCol: Int) {
        val meter = findViewById<View>(R.id.d_meter) ?: return
        if (!place.hasNumericScore ||
            model != com.kyronapp.kyron.data.NationalCoverage.MODEL_SCORE_100_HIGH) {
            meter.visibility = View.GONE
            return
        }
        meter.visibility = View.VISIBLE
        val fill = findViewById<View>(R.id.d_meter_fill)
        val tick = findViewById<View>(R.id.d_meter_tick)
        fill.setBackgroundColor(scoreCol)
        meter.post {
            val w = meter.width
            val target = (w * (place.score.coerceIn(0, 100) / 100f)).toInt()
            tick.x = w * 0.85f - 1
            if (Ui.reduceMotion(this)) {
                fill.layoutParams = fill.layoutParams.apply { width = target }
            } else {
                android.animation.ValueAnimator.ofInt(0, target).apply {
                    duration = 650
                    interpolator = android.view.animation.DecelerateInterpolator(2f)
                    addUpdateListener {
                        fill.layoutParams = fill.layoutParams.apply {
                            width = it.animatedValue as Int
                        }
                    }
                    start()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
