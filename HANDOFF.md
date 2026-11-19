# Live apply handoff

Branch `work/liveapply` (on `work/net`). Report with the root cause, commits and exact test lines:
`~/Projects/.coord/liveapply-report.md`. The previous handoff (net worker) is in `b27ab822`.

## Why Apply live closed the app

Every release changes `dex/classes.dex` (1.2.293 -> 1.2.294 also changes libmain, the slim data
bundle, the loader and libnativeui). The AndySU host (transparent mode) only swaps a release
in-process when nothing under `dex/` changed (`native_live`), so it reported every release as not
live. The app then took its restart path for both Apply now and the automatic apply in the menu:
`ReplaceGuard.beforeReplace/beginReplace` finished the screens and stopped the services, and
`restartToApply()/restartNow()` had the host relaunch the process through Phoenix. On the tablet
that is the app closing. A native-only release could also end there: `UpdateClient.applyNow()`
relaunches whenever the app's `LiveSwapHandler` declines.

## What happens now

`LiveApply` (pure, `LiveApplyTest`) decides from the running and staged slot indexes
(`no_backup/andysu/slots/<id>/.andysu-slot.json`):

| staged release changes | Apply live / automatic apply in the menu |
|---|---|
| native code and content only (`native_live`) | the host's own live swap, called through `applyLive` directly (public `UpdateClient.applyLive()` on a newer host, the host's `AndySU.applyLive()` by reflection on the installed API 3 hosts), so a decline cannot become a relaunch |
| the engine (libmain, curl/png/d0) or the game data, Java too | the app's own swap (`EngineSwap.swapEngine`): the loader swaps libmain in this process, the data is unpacked from the staged slot's `assets/xonotic-slim-data.zip` (`GameData.useLiveBundle`), the host stays on its slot and is asked to promote the staged slot at the next start, when the release's Java code takes over. Updates screen: "Version X is running ... the app's own screens update the next time Godnotic starts" + Restart now (tonal) |
| only Java/Kotlin, Java-loaded libraries (loader, libnativeui, SDL, Rust core) or Java's assets | no swap and no automatic restart: "This update only changes the app itself ... applies the next time Godnotic starts", Restart now (primary), one toast per release |
| the host APK (shell update) | unchanged: Android's installer |

Restart now (`updates.restart` -> request `restart`) is the only path left to `restartToApply`.
Apply live never restarts (`scripts/test-android-updates.sh` checks the source for it).
A refused swap says why (loader, java_abi, trial, a match) and leaves the running engine alone.

`java_abi` (`android/app/src/main/cpp/andysu/java_abi.h`, now 2, was a constant 1): the loader
refuses an engine whose contract with the running Java side differs. `scripts/test-java-abi.sh`
(tests workflow) fails when that surface changes without the fingerprint being updated; bump the
number too unless an older Java side still works with the new engine.

## Tests

- `scripts/test-android-updates.sh` (JVM): `LiveApplyTest` with the real 1.2.293 -> 1.2.294 channel
  components, the bridge gate, source checks that Apply live and the automatic apply never restart.
- Gradle `:app:compileDebugJavaWithJavac :app:testDebugUnitTest`.
- `scripts/test-java-abi.sh`.
- `scripts/test-andysu-desktop-swap.sh`: the real loader + a desktop engine library with the swap
  hooks (`-DANDYSU_SWAP`), offscreen, no network: swap in one process with the menu back, the
  in-process rollback, the java_abi refusal. Needs `BASEDIR` with game data.

## Not verified (needs the tablet)

- The first release carrying this code still arrives by the old path (the running Java decides how a
  release applies): from the release after it, Apply live swaps the engine and data in-process.
- The detached swap on Android: data unpack from the slot file, the brief black frame while the
  engine restarts on the same SDL surface, the native Home screen staying put over it, the next
  start promoting the staged slot (API 3 hosts use the `apply_pending` file).
- A swapped engine gets curl/png/d0 by bare name, i.e. the copies the first engine loaded; a release
  that changes those libraries runs the old copies until the next start.

## AndySU

Branch `liveapply-no-relaunch` in `/tmp/andysu-liveapply` (not pushed): Host API level 4
`UpdateClient.applyLive()` that never restarts; `applyNow()` keeps a declined/blocked live swap
staged instead of relaunching; a `live_fallback = restart` waits for the background while an activity
is resumed. Needs a new host APK (host template) to reach installed apps. In-process Java reload is
not possible in transparent mode (D21/D29: the app's classes run under their real names from the
class loader the process booted with); see the report for what it would take.
