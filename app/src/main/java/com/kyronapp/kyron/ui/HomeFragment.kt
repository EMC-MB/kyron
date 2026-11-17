package com.kyronapp.kyron.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.kyronapp.kyron.KyronApplication
import com.kyronapp.kyron.MainActivity
import com.kyronapp.kyron.R
import com.kyronapp.kyron.data.ExploreState
import com.kyronapp.kyron.data.NationalCoverage
import com.kyronapp.kyron.data.Regions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Landing tab: greeting + region context, a hero search that routes into
 * Browse with the query pre-filled, honest per-region stats, and shortcut
 * cards into the other tabs / Coverage. Read-only — every action navigates.
 */
class HomeFragment : Fragment() {

    private lateinit var greeting: TextView
    private lateinit var regionSub: TextView
    private lateinit var search: EditText
    private lateinit var statPlaces: TextView
    private lateinit var statWatched: TextView
    private lateinit var statSync: TextView
    private lateinit var modelTitle: TextView
    private lateinit var modelBody: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        greeting = v.findViewById(R.id.home_greeting)
        regionSub = v.findViewById(R.id.home_region_sub)
        search = v.findViewById(R.id.home_search)
        statPlaces = v.findViewById(R.id.stat_places)
        statWatched = v.findViewById(R.id.stat_watched)
        statSync = v.findViewById(R.id.stat_sync)
        modelTitle = v.findViewById(R.id.home_model_title)
        modelBody = v.findViewById(R.id.home_model_body)

        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                openBrowse(search.text.toString().trim())
                true
            } else false
        }

        val act = activity as? MainActivity
        v.findViewById<View>(R.id.action_browse).setOnClickListener { openBrowse("") }
        v.findViewById<View>(R.id.action_map).setOnClickListener { act?.selectTab(2) }
        // Notification rules + inbox live on their own screen — the
        // fourth tab is Account now.
        v.findViewById<View>(R.id.action_watch).setOnClickListener {
            startActivity(Intent(requireContext(), AlertsActivity::class.java))
        }
        v.findViewById<View>(R.id.action_regions).setOnClickListener {
            startActivity(Intent(requireContext(), CoverageActivity::class.java))
        }

        // One orchestrated entrance per session — fragments stay alive,
        // so revisiting Home never replays it.
        if (!playedIntro) {
            playedIntro = true
            Ui.animateIn(greeting, 0)
            Ui.animateIn(regionSub, 50)
            Ui.animateIn(search, 90)
            Ui.animateIn(v.findViewById(R.id.home_stats), 170)
            Ui.animateIn(v.findViewById(R.id.home_tiles), 230)
            Ui.animateIn(v.findViewById(R.id.home_model_card), 300)
        }
    }

    private var playedIntro = false

    override fun onResume() {
        super.onResume()
        val app = (activity?.application as? KyronApplication) ?: return
        val info = app.regions.activeInfo()

        greeting.text = when (java.util.Calendar.getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> getString(R.string.home_greeting_morning)
            in 12..16 -> getString(R.string.home_greeting_afternoon)
            else -> getString(R.string.home_greeting_evening)
        }
        regionSub.text = getString(R.string.home_region_sub, info.displayName)

        val model = Regions.nationalModel(info)
        modelTitle.text = getString(R.string.home_model_title, info.displayName)
        modelBody.text = getString(
            when (model) {
                NationalCoverage.MODEL_SCORE_100_HIGH -> R.string.home_model_score
                NationalCoverage.MODEL_PENALTY_LOW -> R.string.home_model_penalty
                NationalCoverage.MODEL_LETTER_GRADE -> R.string.home_model_grade
                NationalCoverage.MODEL_PASS_FAIL -> R.string.home_model_passfail
                NationalCoverage.MODEL_DISPOSITION -> R.string.home_model_disposition
                NationalCoverage.MODEL_VIOLATIONS_ONLY -> R.string.home_model_violations
                else -> R.string.home_model_unknown
            }
        )
        bindModelKey(model)

        statWatched.text = "%,d".format(app.prefs.favorites.size)
        val syncMs = app.prefs.lastSyncMs
        statSync.text = if (syncMs <= 0L) getString(R.string.home_sync_never)
        else {
            val hrs = (System.currentTimeMillis() - syncMs) / 3_600_000L
            when {
                hrs < 1 -> getString(R.string.home_sync_lt1h)
                hrs < 48 -> getString(R.string.home_sync_hours, hrs)
                else -> getString(R.string.home_sync_days, hrs / 24)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val places = withContext(Dispatchers.IO) {
                app.repository.regionPlaceCount(info.regionId)
            }
            if (Ui.reduceMotion(requireContext()) || places <= 0) {
                statPlaces.text = "%,d".format(places)
            } else {
                ValueAnimator.ofInt(0, places).apply {
                    duration = 700
                    interpolator = android.view.animation.DecelerateInterpolator(2f)
                    addUpdateListener {
                        statPlaces.text = "%,d".format(it.animatedValue)
                    }
                    start()
                }
            }
        }

        // "Watching": the integrated Favorites surface — starred places
        // with their current result, worst-looking first.
        val watched = app.repository.places.value
            .filter { app.prefs.favorites.contains(it.id) }
            .sortedWith(compareBy { if (it.hasNumericScore) it.score else Int.MAX_VALUE })
        bindWatch(watched, model)

        // "Needs attention": the region's worst current results, sorted
        // model-aware (highest demerits / lowest scores first).
        viewLifecycleOwner.lifecycleScope.launch {
            val worst = withContext(Dispatchers.IO) {
                app.repository.searchPlaces(
                    com.kyronapp.kyron.data.PlaceQuery(limit = 5),
                    info.regionId, model
                )
            }
            bindNeeds(worst, info.displayName, model)
        }
    }

    private fun bindWatch(
        places: List<com.kyronapp.kyron.data.Place>,
        model: String
    ) {
        val block = view?.findViewById<View>(R.id.home_watch_block) ?: return
        val list = view?.findViewById<android.widget.LinearLayout>(R.id.home_watch_list) ?: return
        list.removeAllViews()
        if (places.isEmpty()) {
            block.visibility = View.GONE
            return
        }
        block.visibility = View.VISIBLE
        view?.findViewById<TextView>(R.id.home_watch_title)?.text =
            getString(R.string.home_watch_title, places.size)
        places.take(8).forEach { p ->
            list.addView(placeRow(p, model))
        }
    }

    private fun bindNeeds(
        places: List<com.kyronapp.kyron.data.Place>,
        regionName: String,
        model: String
    ) {
        val block = view?.findViewById<View>(R.id.home_needs_block) ?: return
        val list = view?.findViewById<android.widget.LinearLayout>(R.id.home_needs_list) ?: return
        list.removeAllViews()
        if (places.isEmpty()) {
            block.visibility = View.GONE
            return
        }
        block.visibility = View.VISIBLE
        view?.findViewById<TextView>(R.id.home_needs_title)?.text =
            getString(R.string.home_needs_title, regionName)
        places.forEach { p ->
            list.addView(placeRow(p, model))
        }
    }

    /** Placard place row: score rendered like the posted card — banded
     *  tile, condensed numeral — next to name + date/city, tap → Detail.
     *  Shared by the Watching and Needs-attention lists. */
    private fun placeRow(
        p: com.kyronapp.kyron.data.Place,
        model: String
    ): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun d(v: Float) = (v * dp).toInt()
        val scoreCol = Ui.scoreColor(ctx, p, model)

        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(d(10f), d(10f), d(14f), d(10f))
            isClickable = true; isFocusable = true
            stateListAnimator = android.animation.AnimatorInflater
                .loadStateListAnimator(context, R.animator.press_scale)
            setOnClickListener {
                (activity as? MainActivity)?.openPlace(p.id)
            }
        }

        // Placard: white tile + score-colored top band + big condensed numeral.
        val placard = FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.placard_tile)
            clipToOutline = true
        }
        placard.addView(
            View(ctx).apply { setBackgroundColor(scoreCol) },
            FrameLayout.LayoutParams(-1, d(4f), Gravity.TOP)
        )
        placard.addView(
            TextView(ctx).apply {
                text = p.badgeText
                setTextColor(scoreCol)
                textSize = if (p.hasNumericScore) 26f else 13f
                typeface = ResourcesCompat.getFont(ctx, R.font.barlowcondensed_bold)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        row.addView(placard, android.widget.LinearLayout.LayoutParams(d(56f), d(62f)))

        val texts = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(14f), 0, 0, 0)
        }
        texts.addView(TextView(ctx).apply {
            text = p.name
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.ink))
            textSize = 15f
            typeface = ResourcesCompat.getFont(ctx, R.font.barlow_semibold)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(ctx).apply {
            text = buildString {
                if (p.date.isNotBlank()) append(Ui.fmtDate(p.date))
                if (p.city.isNotBlank()) {
                    if (isNotEmpty()) append("  ·  ")
                    append(Ui.titleCase(p.city))
                }
            }.ifBlank { p.bandLabel }
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.muted))
            textSize = 12.5f
            setPadding(0, d(2f), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(texts, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        row.layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = d(10f)
        }
        return row
    }

    /** Visual score key under the explainer — colored dots + labels so the
     *  scale is seen, not decoded. Hidden for models with no fixed key. */
    private fun bindModelKey(model: String) {
        val row = view?.findViewById<android.widget.LinearLayout>(R.id.home_model_key) ?: return
        row.removeAllViews()
        val green = 0xFF2E9E4F.toInt()
        val lime = 0xFF7AB648.toInt()
        val amber = 0xFFE8A13A.toInt()
        val red = 0xFFD9534F.toInt()
        val chips: List<Pair<String, Int>> = when (model) {
            NationalCoverage.MODEL_SCORE_100_HIGH ->
                listOf("95+ Excellent" to green, "85–94 Okay" to lime, "<85 Needs work" to red)
            NationalCoverage.MODEL_PENALTY_LOW ->
                listOf("0–13 Low" to green, "14–27 Watch" to amber, "28+ High" to red)
            NationalCoverage.MODEL_LETTER_GRADE ->
                listOf("A Pass" to green, "B Follow-up" to amber, "C Fail" to red)
            NationalCoverage.MODEL_PASS_FAIL ->
                listOf("Pass" to green, "Conditional" to amber, "Fail" to red)
            else -> emptyList()
        }
        if (chips.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        val dp = resources.displayMetrics.density
        chips.forEach { (label, color) ->
            val chip = TextView(requireContext()).apply {
                text = label
                textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.ink))
                setBackgroundResource(R.drawable.chip_off_bg)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                val dot = androidx.core.content.ContextCompat.getDrawable(
                    context, R.drawable.ic_dot
                )?.mutate()
                dot?.setTint(color)
                setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
                compoundDrawablePadding = (6 * dp).toInt()
            }
            row.addView(chip, android.widget.LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = (8 * dp).toInt()
            })
        }
    }

    /** Routes into Browse (tab index 1) with the query staged in
     *  [ExploreState.pendingSearch]; Browse applies it on show. */
    private fun openBrowse(q: String) {
        ExploreState.pendingSearch = q
        (activity as? MainActivity)?.selectTab(1)
    }
}
