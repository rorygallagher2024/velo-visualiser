# CLAUDE.md

Working notes for this repo — a reminder to keep, not a rulebook. Use judgment; don't be dogmatic or over-engineer.

Velo is an Android low-latency audio visualiser + smart-lighting app (Kotlin, OpenGL ES, C++/Oboe).

## Code quality — favour future maintainability

The app shipped a big refactor to tame a 2,500-line `MainActivity`. Don't undo that drift:

- **`MainActivity` is a thin coordinator, not a dumping ground.** New feature UI + state goes in a focused controller in the `ui/` package (see `PerfOverlayController`, `LightingController`, `MenuSheetController`, `DisplayModeController`) — instantiated in `onCreate` with a `bind()` and forwarded lifecycle (`onResume`/`onPause`/`onDestroy`).
- **Keep functions and classes small.** If a function grows long or branchy (rising cyclomatic complexity), extract a well-named private helper. E.g. a `bind()` shouldn't carry a big inline gesture/listener block — pull it into its own method.
- **One responsibility per file.** GL scenes live one-per-file under `gl/`; each integration/concern in its own package.
- **detekt is the guard.** It runs on `./gradlew check` with complexity/size thresholds (`config/detekt/detekt.yml`); pre-existing debt is grandfathered in `config/detekt/baseline.xml`. Don't add *new* violations — and shrink the baseline as things get split up.

## Before claiming done

- **Validate with `./gradlew check`** — it bundles detekt (complexity/size), Android lint, *and* the JVM unit tests, including `ShaderValidationTest` (headless GLSL validation). Use `assembleDebug`/`installDebug` for the fast device loop, but `check` is the gate before calling something done. Note: **`assembleDebug` runs none of these** (no tests, no lint), so a green `assembleDebug` proves less than it looks.
- GLSL shaders compile at **runtime**. `ShaderValidationTest` now catches the compile-time class headlessly — reserved words like `sample`, plus full syntax/type checking when `glslangValidator` is on PATH (`brew install glslang`). But a shader can compile cleanly and still *render* wrong, so verify visuals on a device/emulator.

## Pull requests

PR bodies must mirror `.github/PULL_REQUEST_TEMPLATE.md` — same sections, same order:

- **Description** — a real summary and the motivation. Link the issue with `Fixes #N`, or delete that line if there isn't one.
- **Type of change** — delete the options that don't apply; tick the one(s) that do.
- **How Has This Been Tested?** / **Checklist** — tick only what actually happened, and fill in the blanks (device, API level, metrics). If you couldn't run the app — e.g. a cloud sandbox with no device or emulator — leave those boxes unticked and say so in a sentence. "No new warnings or lint errors" may only be ticked after a passing `./gradlew check`. An unticked box with an honest note beats a ticked box that's a lie.

The point is simply to consider the next person (or session) before adding code — not to gold-plate.
