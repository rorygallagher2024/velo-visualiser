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

- Build it: `./gradlew assembleDebug` (and `./gradlew :app:detekt` if you touched code structure).
- GLSL shaders compile at **runtime**, so a green build doesn't prove a shader is valid — those need a device/emulator to verify.

The point is simply to consider the next person (or session) before adding code — not to gold-plate.
