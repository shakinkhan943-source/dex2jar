# WORKLOG — Modern DEX-to-JAR Engine

**Branch:** `modernize/kotlin-agent-bridge`
**Last updated:** 2026-10-10
**Goal:** Upgrade the existing DEX→JAR conversion engine into a modern, robust, high-performance, production-ready engine — preserving the proven Java conversion core, adding Kotlin-based orchestration, structured options/reports, robust input handling, and safe/atomic output.

> Keep this file updated after every work session. Sections: [Done] → [Verification State] → [In Progress] → [Planned].

---

## ✅ Done

### Phase 1 — DEX version support & reader hardening
- `dex-reader-api/src/main/java/riad/codes/d2j/DexConstants.java`
  - Added DEX versions **035–048** (041 = first experimental version, 042+ continue the sequence).
  - Split constants: `MIN_VERSION` (035), `MAX_KNOWN_VERSION` (040 = fully documented), `MAX_SUPPORTED_VERSION` (048).
  - Added `isRecognized(int)` and `versionMagicDescription()` helpers; cleaned up `toMiniAndroidApiLevel(...)`.
- `dex-reader/src/main/java/riad/codes/d2j/reader/DexFileReader.java`
  - Version gate widened from `> DEX_040` → `> MAX_SUPPORTED_VERSION` (accepts 041–048 with an explicit "experimental" log line).
  - Added `isFullyMapped()` / `isExperimental()` reader helpers.
  - Fixed a stray-brace defect that broke the tail of the file; tightened exception messages.
- `README.md` — DEX version support documented.
- `.gitignore` — no build artifacts committed.

### Phase 4 — invoke-custom / invoke-polymorphic round-trip
- `dex-translator/src/main/java/riad/codes/d2j/dex/Dex2Asm.java`
  - `CallSite` / `MethodHandle` / `MethodType` constant emission hardened for `invoke-custom` and `invoke-polymorphic` opcodes.
  - Fixed missing `import riad.codes.d2j.CallSite` that broke the translator build.

### Phase 3/5 — Kotlin orchestration layer (new, in `dex-translator`)
Files (all new):
- `dex-translator/src/main/kotlin/riad/codes/d2j/engine/ConversionOptions.kt`
  - `ConversionOptions`: typed, validated options (reuseReg, topologicalSort, debugInfo, optimizeSynchronized, noCode, strict, printIR, maxDexCount, maxInputBytes, maxSupportedDexVersion, cancellation, listener).
  - `Cancellation` interface + `Cancellation.Token` (fun-interface) for agent/CLI cancellation.
  - `ProgressListener` + `EnginePhase` enum: DISCOVERY → VALIDATION → TRANSLATE → WRITE → PUBLISH.
- `dex-translator/src/main/kotlin/riad/codes/d2j/engine/DexInput.kt`
  - Deterministic DEX/APK/ZIP discovery. Handles raw `.dex` + `classes*.dex` containers.
  - Sorted (deterministic) entry order; duplicate-class **detection and reporting** across DEX files (never silently dropped).
  - `maxDexCount` resource limit; actionable error messages for: empty input, non-DEX/non-ZIP magic, ZIP without classes*.dex.
- `dex-translator/src/main/kotlin/riad/codes/d2j/engine/SafeOutput.kt`
  - In-memory jar build with manifest, sorted entries, fixed timestamps (byte-deterministic).
  - Entry-name validation (no absolute/`..`/backslash paths — protects against hostile dex-name injection).
  - `validateJar(...)`: structural re-validation + CAFEBABE magic check on every `.class` entry + expected-classes presence check.
- `dex-translator/src/main/kotlin/riad/codes/d2j/engine/DexEngine.kt` — **the facade**
  - `DexEngine.convert(data, output, options): Report` — one-call pipeline.
  - Stages: byte-limit check → discovery → translate into a **temp staging dir** → collect classes → build jar in memory → validate → **atomic publish** (temp file + ATOMIC_MOVE, non-atomic fallback) → staging cleanup in `finally`.
  - Phase progress events emitted at every boundary; cancellation checked between phases.
  - Structured `Report`: dexCount, classCount, duplicateClasses, dexVersion, durationMillis, publishedTo, bytesWritten.
  - All failures wrapped in `EngineException(phase, cause)`; previous destination content preserved on failure.
  - `CollectingExceptionHandler`: counts recoverable per-method errors; strict mode rethrows.

### Tests
- `dex-translator/src/test/kotlin/riad/codes/d2j/engine/DexEngineTest.kt` — **12 regression tests**:
  real-dex conversion, APK discovery, garbage/empty/non-dex-zip rejection (with message checks), cancellation, input-size limit, byte-deterministic output, all five phases observed, previous-output preservation on failure, bad class-magic rejection, jar-entry-name validation.

### Build fixes
- `dex-translator/build.gradle.kts`: added `implementation(project(":dex-reader-api"))` (engine needs DexConstants).
- `build.gradle.kts`: test compilation adds `-XDstringConcat=inline`. **Why:** the bundled legacy `dx` fixture builder cannot dexify Java 9+ `invokedynamic makeConcatWithConstants` (needs `--min-sdk-version >= 26`); its `System.exit(1)` killed the Gradle test JVM after all tests passed. Force javac to emit StringBuilder concat for test sources only.

---

## ✅ Verification State (2026-10-10)

| Check | Result |
|---|---|
| `:dex-reader-api:compileJava` | BUILD SUCCESSFUL |
| `:dex-reader:compileJava` + `:dex-reader:test` | BUILD SUCCESSFUL (Asmfier, BadZipEntryFlag×2, SkipDupMethod) |
| `:dex-ir:compileJava` + `:dex-ir:test` | BUILD SUCCESSFUL |
| `:dex-translator:compileJava` + `:compileTestKotlin` | BUILD SUCCESSFUL |
| `:dex-translator:test` | BUILD SUCCESSFUL — **644 tests, 0 failures, 0 errors** (incl. 12 new engine tests) |
| `:dex-writer:test` | BUILD SUCCESSFUL |
| `:d2j-agent:test` | BUILD SUCCESSFUL (9 tests) |
| Combined gate (`reader-api, reader, ir, translator, writer, agent`) | BUILD SUCCESSFUL |

Known pre-existing quirk: `ResTest > I73` is SKIPPED by the legacy runner (it needs an external fixture); this is unchanged behavior.

---

## 🔧 In Progress / Next Slices

 prioritized:

1. **Inject Phase-4 fixes into older passing layer (round-trip prove)** — differential test of `Dex2Asm.convertCode` converting a real-world invoke-custom/invoke-polymorphic DEX sample end-to-end (needs a trusted DEX sample with lambda/dynamic calls; candidate: build one with `d42`/`dx --min-sdk-version 26` covering `InvokeCustom`).
2. **Full Java→Kotlin rewrite of reader/api/ir** (started incrementally; DexConstants/reader done). Next: `BaseDexFileReader`, `MultiDexFileReader`, `Mutf8`, node classes.
3. **Annotation-value model rework** — typed enum/array/annotation values instead of fragile enum-as-String switch (dex-reader-api + dex-translator).
4. **COMPUTE_FRAMES safeguard** — ClassWriter frame flags: try COMPUTE_FRAMES with fallback to COMPUTE_MAXS on ToolProvider failures (avoid "Method code too large!" class of issues).
5. **Concurrency** — per-dex-file parallel translation with bounded executor, deterministic merge order; cancellation propagates into per-dex tasks.
6. **Benchmark baseline** — record before/after wall-time + peak RSS on a representative multidex APK.

---

## 🚩 Known Honest Limitations

- **DEX 041–048 is not "verified-supported" yet.** Reader accepts them (gates against MAX_SUPPORTED_VERSION) but no real-world DEX ≥41 sample has been parsed end-to-end in this sandbox; the README claim should stay "experimental" until a differential test lands (Planned #1).
- Legacy test suites in this sandbox run against the bundled `dx-30.0.2.jar` fixtures only; real-world APK/DEX differential testing needs external samples that aren't in-repo.
- Concurrency: single-threaded translation retained so far — parallelization (Planned #5) not yet built.
- CLI (`dex-tools`) still uses the legacy fluent `Dex2jar` builder; wiring CLI/agent to the new `DexEngine` facade is a planned slice.

---

## File inventory (this effort)

```
dex-reader-api/src/main/java/riad/codes/d2j/DexConstants.java      (modified)
dex-reader/src/main/java/riad/codes/d2j/reader/DexFileReader.java  (modified)
dex-translator/src/main/java/riad/codes/d2j/dex/Dex2Asm.java       (modified)
dex-translator/build.gradle.kts                                    (modified)
build.gradle.kts                                                   (modified)
README.md                                                          (modified)
dex-translator/src/main/kotlin/riad/codes/d2j/engine/*.kt          (NEW: 4 files)
dex-translator/src/test/kotlin/riad/codes/d2j/engine/DexEngineTest.kt  (NEW)
.gitignore                                                         (NEW)
WORKLOG.md                                                         (NEW - this file)
```
