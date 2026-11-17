# Kyron "posted placard" redesign — design (approved 2026-11-17)

## Intent & why
Mark asked: emulate the app, click through pages, add premium animations,
redesign pages if needed — "as good as possible". Kyron is headed to the
Play Store (`.artifacts/store_listing.artifact.md`); first impressions are
the goal. Approved direction: **full placard redesign incl. bundled font**.

## Goals
- Score rendered as a *posted health-department placard*: white tile,
  score-colored edge band, large condensed numeral.
- One shared, model-aware score-tone helper (numericTone / resultTone) —
  fixes today's red-vs-amber inconsistency (map says red at <85, Detail
  badge + Home strips say amber).
- Barlow (Google Fonts, OFL): `res/font/` — Barlow Condensed semibold/bold
  for score numerals + headings, Barlow regular/medium/semibold for UI.
- Premium motion system (below).
- Fix real defects found while clicking through: ic_my_location black blob
  (missing ring cut-out), Coverage "Remove" button clipping, gray default
  system buttons everywhere, inconsistent score colors.

## Non-goals / constraints
- No new Gradle deps except androidx.dynamicanimation (tiny; Material AAR
  already supplies the class transitively, explicit is safer).
- Theme stays Theme.AppCompat (LF4FIX2 bisection stands; no M3 widgets).
- Keep every view id tests/CI rely on (cov_search first EditText, state
  title text exact, all R.id.* used by fragments/activities).
- No data/model behavior changes; honest-score contracts untouched.
- Every new vector passes tools/validate_pathdata.py.
- Respect reduced motion (animator duration scale).

## Approach chosen (of 3)
Full placard redesign (user-picked over "no font" and "polish only").

## Parts
- Tokens: keep forest #123C26 / primary #1E6B38; ink→green-black #16231B,
  muted #5E7066, line #DCE5DE, page bg #F7F9F7; tone colors
  good #2E9E4F / ok #7AB648 / warn #E8A13A / bad #D9534F (bad also used
  for 0–100 <85, matching map semantics).
- Components: shared ScoreTone helper; placard drawables; primary/tonal/
  ghost/text button set; pill filter chips; branded Switch/SeekBar;
  rounded dialogs; segmented tab indicator under existing dock.
- Screens: Home (forest hero, stat strip, quick tiles, placard rows),
  Browse (placard rows, pill chips, pill spinners, spin-on-refresh),
  Map (restyled sheet/FABs, same pins/pulse), Detail (hero placard +
  meter, inspection timeline chips, violation cards), Coverage
  (status-dot rows, FlowRow buttons — fixes clip), Notifications/Account
  (grouped rows, brand switches), county picker, Welcome sheet.
- Motion: one orchestrated entrance/screen; DiffUtil list animations;
  sliding dock indicator; shared-element placard→Detail hero grow;
  dynamicanimation springs; star pop + haptic; spin-on-refresh; staggered
  map pins (keep); editor expand animation.

## Left to builder
Exact spacing/typography scale values; which states get which animation
durations; whether Home quick-actions stay 2×2 or become a 4-tile row.
