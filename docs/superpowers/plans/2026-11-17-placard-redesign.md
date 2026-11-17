# Kyron "posted placard" Redesign Implementation Plan

**Goal:** Redesign every screen around a "posted placard" score motif with a bundled Barlow typeface and a premium motion system, then verify on emulator.

**Architecture:** Res-first redesign — new tokens/drawables/fonts/animators plus layout rewrites; Kotlin changes are limited to binding, a shared ScoreTone/Ui helper, DiffUtil adapter, tab indicator, shared-element transition wiring. No new widgets (no Material components — theme stays AppCompat); only dependency add is androidx.dynamicanimation.

**Tech Stack:** Kotlin, Android Views (AppCompat), aapt2/Gradle, osmdroid, dynamicanimation, bundled OFL Barlow TTFs.

**Spec:** `docs/superpowers/specs/2026-11-17-placard-redesign-design.md`

## Global Constraints

- Theme stays `Theme.AppCompat.Light.NoActionBar`; no `com.google.android.material` widget classes in layouts (LF4FIX2).
- Keep all `R.id.*` referenced by Kotlin/tests; Coverage's first `EditText` stays `cov_search`; state title `TextView` holds exactly the state name.
- Honesty contracts unchanged: NO_SCORE never banded; models come from `NationalCoverage.numericTone(model,score)`/`Place.resultTone`.
- Every new vector drawable passes `python tools/validate_pathdata.py app/src/main/res`.
- Motion helper `Ui.reduceMotion(ctx)` (animator-duration-scale check) gates every decorative animation.
- Final verify = `./gradlew assembleDebug` + pathdata gate + emulator screenshots; per-task commits.

## Review Focus

- <85 score in a 0–100 region now renders red (tone 3) on Detail/Home too — intentional, matches map; confirm visual acceptance.
- Place names/addresses may be UPPERCASE in data — display-format helper must title-case defensibly (keep ZIPs, N/S/E/W).
- Shared-element transition must never crash when repository hasn't loaded: Detail enters with placeholder and animates in.
- Emulator API 37 ≠ user's Fold7 — verify no API>29 APIs used (haptic CONFIRM constant is API 30+: guard).
- osmdroid Marker alpha animation already exists — new pin visuals reuse pillIcon() with new colors only.

---

### Task 1: Tokens, fonts, components, motion helpers

**Files:**
- Create `app/src/main/res/font/` files: Barlow-{Regular,Medium,SemiBold,Bold}.ttf, BarlowCondensed-{SemiBold,Bold}.ttf (curl from github.com/google/fonts raw), `OFL.txt`, `font_barlow.xml`, `font_barlow_condensed.xml`
- Modify `app/src/main/res/values/colors.xml`, `values/themes.xml`
- Create `app/src/main/res/values/styles.xml` — `KyronButton`, `KyronButton.Tonal`, `KyronButton.Text`, `KyronButton.Primary`
- Create drawables: `placard.xml` (white card + score-colored top band layer-list), `placard_hero.xml`, `btn_tonal.xml`, `btn_text_ripple.xml`, `chip_pill.xml`, `chip_pill_on.xml`, `spinner_pill.xml`, `dialog_bg.xml`, `stat_dot.xml`, `ic_bell.xml`, `ic_check_sm.xml`, `ic_chevron.xml`; fix `ic_my_location.xml` (canonical gps_fixed w/ ring cut-out)
- Create `anim/slide_up_fade.xml`
- Kotlin: `ui/Ui.kt` — `dp()`, `toneColor(tone:Int)`, `scoreColor(place,model)`, `titleCase()`, `fmtDate()`, `reduceMotion()`, `springTo()`; `ui/FlowRowLayout.kt` (children wrap at width, gaps)

**Steps:**
- [ ] 1. Download 6 TTFs + OFL.txt into `app/src/main/res/font/`; write family XMLs.
- [ ] 2. colors.xml: `ink #16231B`, `muted #5E7066`, `line #DCE5DE`, `bg #F7F9F7`, `tonal #E4EFE7`, tones `#2E9E4F/#7AB648/#E8A13A/#D9534F`.
- [ ] 3. themes.xml: `android:fontFamily`→`@font/font_barlow`, `colorControlActivated #1E6B38`, `buttonStyle`/`android:buttonStyle`→`KyronButton`, `alertDialogTheme`, `android:dialogTheme`; keep status `#123C26`, nav bar `#F7F9F7`.
- [ ] 4. Drawables + styles; fix `ic_my_location`.
- [ ] 5. `Ui.kt` + `FlowRowLayout`.
- [ ] 6. Verify: `python tools/validate_pathdata.py app/src/main/res` PASS; `./gradlew :app:mergeDebugResources` green. Commit.

### Task 2: App chrome — header, dock indicator, region chip

**Files:** Modify `layout/activity_main.xml` (56dp forest bar: BarlowCondensed-bold "KYRON" wordmark, region chip w/ chevron, bell `notif_btn` → AlertsActivity; dock gets 4dp sliding `dock_indicator` under tab row), `MainActivity.kt` (indicator slide via ValueAnimator, `Ui.reduceMotion` gate, haptic on select, bell wiring; keep frag_in/out).
**Interfaces:** Consumes `Ui.springTo`/`reduceMotion`. Produces `R.id.dock_indicator`, `R.id.notif_btn`, `R.id.header_title`.
- [ ] 1. Layout + Kotlin; build; screenshot header/dock; commit.

### Task 3: Home — hero, stats, quick tiles, placard rows

**Files:** Rewrite `layout/fragment_home.xml` (forest hero: greeting + region subtitle + search pill; stat strip card overlapping band; 4-tile shortcut row; model explainer w/ band key; watch/needs sections → placard rows), `HomeFragment.kt` (`placeRow` → placard mini-tile w/ band + condensed score + titleCase name + fmtDate; one-shot entrance stagger via `playedIntro`; stat count-up).
- [ ] 1. Layout + Kotlin; build; screenshot; commit.

### Task 4: Browse — placard rows, filters, DiffUtil

**Files:** Rewrite `layout/item_place.xml` (banded placard tile ~68×76dp + texts + star inside row on tonal bg), `layout/fragment_browse.xml` (tonal guide card, pill chips, `spinner_pill` bgs, refresh icon-button, empty state), `PlaceAdapter.kt` (DiffUtil on `submit()` — `DiffUtil.calculateDiff` keyed on id/eventKey → `dispatchUpdatesTo(this)` + DefaultItemAnimator; `Ui.scoreColor`, titleCase, fmtDate, star tint state).
- [ ] 1. Implement; build; screenshot list + filter-change animation; commit.

### Task 5: Detail — hero placard, shared element, meter

**Files:** Rewrite `layout/activity_detail.xml` (forest hero: back/star/share icon buttons, centered ~132dp placard card w/ score + 0–100 meter ticked at 85, name/addr/source; actions row; history timeline chips; why card; violations w/ point pills), `DetailActivity.kt` (`postponeEnterTransition`/`startPostponedEnterTransition`, meter + count-up gated by reduceMotion), `MainActivity.openPlace` → `ActivityOptionsCompat.makeSceneTransitionAnimation` w/ `transitionName="placard"` on item placard; theme `android:windowContentTransitions true`.
- [ ] 1. Implement; build; tap-through; screenshot; commit.

### Task 6: Map polish

**Files:** `layout/fragment_map.xml` (FABs → one segmented white pill; status pill → white w/ green dot + elevation; place sheet → top handle + placard badge + tonal buttons), `MapFragment.kt` (pin colors → tone palette via `Ui`; keep alpha stagger + pulse).
- [ ] 1. Implement; build; screenshot map + sheet; commit.

### Task 7: Coverage, Notifications, Account, dialogs, welcome

**Files:** `layout/activity_coverage.xml` + `CoverageActivity.kt` (status dot + colored status text; buttons in `FlowRowLayout` — fixes "Remove" clip; tonal download w/ progress text; **state title TextView stays exactly `jur.name`** — CI exact match; `cov_search` stays first EditText); `layout/activity_alerts.xml` + `AlertsActivity.kt` (brand switches via theme, preset chips → pills, editor expand slide); `layout/fragment_account.xml` restyle; `dialog_county_picker.xml` + welcome → rounded `dialog_bg` custom dialog.
- [ ] 1. Implement; build; screenshot each; commit.

### Task 8: Verify & polish pass

- [ ] 1. `./gradlew assembleDebug` + `python tools/validate_pathdata.py app/src/main/res` + `python tools/coverage/coverage_pipeline.py gate` all green.
- [ ] 2. Install on emulator; walk every screen; before/after screenshots; fix spacing/contrast defects; confirm star pop, tab slide, shared element, list diff anim.
- [ ] 3. Reduced-motion sanity: `adb shell settings put global animator_duration_scale 0` → no crashes; restore 1.
- [ ] 4. Final commit.
