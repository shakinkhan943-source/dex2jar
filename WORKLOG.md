# WORKLOG — Modern DEX-to-JAR Engine

**Branch:** `modernize/kotlin-agent-bridge`
**Last updated:** 2026-10-10
**Goal:** A modern, robust, high-performance, production-ready DEX→JAR engine — preserving the proven Java conversion core, adding Kotlin orchestration, structured options/reports, robust input handling, safe/atomic output, container-format support, and parallelism.

---

## ✅ Done

### Phase 1 — DEX version support & reader hardening
- `dex-reader-api/.../DexConstants.java`
  - Fixed the version-word constants (`DEX_040 = 0x00303430`, `DEX_041 = 0x00303431`;
    the magic is read big-endian `'0','4','0','\0' >> 8`).
  - `MAX_SUPPORTED_VERSION = DEX_041`, `MAX_RECOGNIZED_VERSION = DEX_041`;
    `isRecognized()` / `isSupported()`; `toMiniAndroidApiLevel(041) = 35`.
- `dex-reader/.../DexFileReader.java`
  - **DEX 041 (Android 15) container header support.** The 0x78-byte header adds
    `container_size` (0x70) and `header_offset` (0x74); all offsets are
    container-relative. For a standalone file (the layout inside an APK's
    `classes*.dex`) `header_offset == 0`, so container-relative offsets equal
    file-relative offsets and sections parse unchanged. Non-zero `header_offset`
    (a multi-entry container slice) is rejected with an actionable error instead
    of mis-parsed. `getContainerSize()` / `getHeaderOffset()` expose the fields.
  - Rejects the never-shipped `036` and any version above 041.

### Phase 4 — invoke-custom / invoke-polymorphic round-trip
- `dex-translator/.../Dex2Asm.java` — CallSite/MethodHandle/MethodType emission hardened.

### Phase 3/5 — Kotlin orchestration layer (`dex-translator/src/main/kotlin/.../engine`)
- `ConversionOptions.kt` — typed, validated options; `Cancellation` +
  `Cancellation.Token`; `ProgressListener` (phase + per-class); `EnginePhase`;
  `ValidationLevel` (STRUCTURE / BYTECODE); resource limits; `parallelism`;
  `computeFrames`; optional `exceptionHandler` sink; Java-friendly `.cli(...)`.
- `DexInput.kt` — deterministic discovery; Android numeric multidex ordering;
  bounded decompression (zip-bomb guard); header/version pre-validation;
  duplicate-class detection; exposes the **per-DEX reader list** for parallelism.
- `SafeOutput.kt` — deterministic in-memory jar build; entry-name safety; tiered
  validation (STRUCTURE, and BYTECODE via ASM `CheckClassAdapter`).
- `DexEngine.kt` — the facade:
  - validate → translate → collect → build → validate → **atomic publish**;
  - **per-DEX parallel translation on a bounded pool** (`parallelism`; auto =
    one worker per DEX capped by CPU count), merging first-DEX-wins and sorting,
    so parallel output is **byte-identical to sequential**;
  - per-class progress (serialized across workers) and cooperative cancellation
    that propagates across workers;
  - typed `Report`; refuses to publish an empty jar; strict mode.

### Phase 5b — COMPUTE_FRAMES safeguard
- `Dex2jar.computeFrames(true)` uses a `SafeClassWriter` whose
  `getCommonSuperClass` degrades to `java/lang/Object` instead of throwing when
  the Android type hierarchy is off-classpath. Off by default (COMPUTE_MAXS), so
  existing output is unchanged; opt in via `ConversionOptions(computeFrames = true)`.

### Phase 6 — CLI + agent wired to the engine
- `dex-tools/.../Dex2jarCmd.java` — `d2j-dex2jar` routes every file through
  `DexEngine` (STRUCTURE validation). The legacy `-error.zip` dump is preserved
  via the engine's `exceptionHandler` sink (delegate calls serialized for
  parallel safety).
- `d2j-agent` — `JobManager` rewritten (per-job `SharedFlow`, multi-subscriber,
  bounded replay, TTL/retention); `AgentBridgeServer` streams the flow;
  `Dex2JarTool` is engine-backed with progress + cancellation.

### Tests
- `dex-translator/src/test/kotlin/.../DexEngineTest.kt` — **27 tests**, including
  DEX 041 (standalone conversion, container-header parse, non-zero-header_offset
  rejection), COMPUTE_FRAMES validity, parallel-vs-sequential byte equality,
  fixed-timestamp determinism, limits, cancellation, validation tiers, and
  concurrent-conversion isolation.

---

## ✅ Verification State (2026-10-10)

| Check | Result |
|---|---|
| `./gradlew test` (all modules) | **BUILD SUCCESSFUL** |
| `:dex-translator:test` | 702 tests, 0 failures, 1 skipped (incl. 27 engine tests) |
| `:d2j-agent:test` / `:dex-tools:test` / `:dex-reader:test` | 9 / 5 / 4 tests, 0 failures |
| `:dex-ir:test` / `:dex-writer:test` / `:d2j-smali:test` / `:d2j-jasmin:test` | 48 / 4 / 3 / 1 tests, 0 failures |
| Real-world gate | `d2j-dex2jar` on `i_jetty.dex` → 468 classes; `javap -p org.mortbay.jetty.Server` correct |
| Cross-process repeatability | byte-identical jars across separate JVM runs (sha256 match) |
| CLI error dump | invalid input → `…-error.zip`/`summary.txt` as before |

Pre-existing quirk: `ResTest > I73` is SKIPPED by the legacy runner (external fixture).

### Benchmark baseline (`i_jetty.dex`, 887 KB, 468 classes; engine/CLI path)
| Metric | Value |
|---|---|
| Per-conversion wall time (steady state, same JVM) | ≈ **2.7 s** |
| Peak RSS (fresh JVM incl. JVM baseline) | ≈ **133–159 MB** |
| Output | 703,101-byte jar, byte-deterministic |

Note: single-DEX inputs are inherently sequential (one DEX). Parallel translation
speeds up multidex containers; it is correctness-verified (byte-identical to
sequential) but no multidex speedup is claimed here since no large multidex sample
is present in-repo.

---

## 🚩 Known Honest Limitations

- **DEX 041 multi-entry containers**: standalone DEX 041 is fully supported; a
  non-zero `header_offset` slice is rejected with an actionable message.
- **CLI memory profile**: the jar is built in memory before the atomic publish
  (no partial output, higher peak RSS on very large inputs).
- **Bytecode validation** is ASM `CheckClassAdapter` (structural data-flow), not
  full JVM verification (needs a classpath for the type hierarchy).
- **Annotation-value model**: still the legacy enum-as-String switch in
  `dex-reader-api` / `dex-translator`. Reworking it to typed enum/array/annotation
  values is a large, cross-cutting refactor with no user-visible conversion change
  that can be verified in-repo; intentionally not attempted here.

---

## File inventory (this effort)

```
dex-reader-api/src/main/java/riad/codes/d2j/DexConstants.java             (modified)
dex-reader/src/main/java/riad/codes/d2j/reader/DexFileReader.java         (modified: DEX 041)
dex-translator/src/main/java/riad/codes/d2j/dex/Dex2Asm.java              (modified)
dex-translator/src/main/java/riad/codes/d2j/dex/Dex2jar.java              (modified: ClassHook, computeFrames)
dex-translator/src/main/kotlin/riad/codes/d2j/engine/ConversionOptions.kt (new)
dex-translator/src/main/kotlin/riad/codes/d2j/engine/DexInput.kt          (new)
dex-translator/src/main/kotlin/riad/codes/d2j/engine/SafeOutput.kt        (new)
dex-translator/src/main/kotlin/riad/codes/d2j/engine/DexEngine.kt         (new)
dex-translator/src/test/kotlin/riad/codes/d2j/engine/DexEngineTest.kt     (new, 27 tests)
dex-tools/src/main/java/riad/codes/dex2jar/tools/Dex2jarCmd.java          (modified: engine)
d2j-agent/src/main/kotlin/riad/codes/agent/JobManager.kt                  (rewritten)
d2j-agent/src/main/kotlin/riad/codes/agent/AgentBridgeServer.kt           (modified: flow SSE)
d2j-agent/src/main/kotlin/riad/codes/agent/tools/Dex2JarTool.kt           (modified: engine)
README.md, WORKLOG.md                                                     (docs)
```
