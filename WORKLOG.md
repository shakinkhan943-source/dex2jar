# WORKLOG — Modern DEX-to-JAR Engine

**Branch:** `modernize/kotlin-agent-bridge`
**Last updated:** 2026-10-11
**Goal:** A modern, robust, high-performance, production-ready DEX→JAR engine — preserving the proven Java conversion core, adding Kotlin orchestration, structured options/reports, robust input handling, safe/atomic output, container-format support, and parallelism — that downstream projects can consume from **JDK 17**.

---

## ✅ Done

### Phase 1 — DEX version support & reader hardening
- `dex-reader-api/.../DexConstants.java` — version words fixed; `DEX_041` is the
  max supported/recognized; `isSupported()` = 035 and 037–041; API level 35 for 041.
- `dex-reader/.../DexFileReader.java` — **DEX 041 (Android 15) container header.**
  The 0x78-byte header adds `container_size` (0x70) and `header_offset` (0x74);
  all offsets are container-relative. For a standalone file (the layout inside an
  APK's `classes*.dex`) `header_offset == 0`, so container-relative offsets equal
  file-relative offsets and sections parse unchanged. A non-zero `header_offset`
  (a multi-entry container slice) is rejected with an actionable message;
  `getContainerSize()` / `getHeaderOffset()` expose the fields.

### Phase 4 — invoke-custom / invoke-polymorphic round-trip
- `dex-translator/.../Dex2Asm.java` — CallSite/MethodHandle/MethodType emission hardened.

### Phase 3/5 — Kotlin orchestration layer (`dex-translator/src/main/kotlin/.../engine`)
- `ConversionOptions.kt` — typed, validated options; `Cancellation` +
  `Cancellation.Token`; `ProgressListener`; `EnginePhase`; `ValidationLevel`
  (STRUCTURE / BYTECODE); resource limits; `parallelism`; `computeFrames`;
  optional `exceptionHandler` sink; Java-friendly `.cli(...)`.
- `DexInput.kt` — deterministic discovery; Android numeric multidex ordering;
  bounded decompression (zip-bomb guard); header/version pre-validation;
  duplicate-class detection; exposes the **per-DEX reader list** for parallelism.
- `SafeOutput.kt` — deterministic in-memory jar build; entry-name safety; tiered
  validation (STRUCTURE, and BYTECODE via ASM `CheckClassAdapter`); every jar
  entry pinned to one fixed timestamp.
- `DexEngine.kt` — the facade: validate → translate → collect → build → validate
  → **atomic publish**; **per-DEX parallel translation on a bounded pool**
  (first-DEX-wins dedup, output byte-identical to sequential); per-class progress
  (serialized across workers); cooperative cancellation; typed `Report`; refuses
  to publish an empty jar; strict mode.

### Phase 5b — COMPUTE_FRAMES safeguard
- `Dex2jar.computeFrames(true)` uses a `SafeClassWriter` whose
  `getCommonSuperClass` degrades to `java/lang/Object` instead of throwing when
  the Android type hierarchy is off-classpath. Off by default (COMPUTE_MAXS).

### Phase 6 — CLI + agent wired to the engine
- `dex-tools/.../Dex2jarCmd.java` — `d2j-dex2jar` routes through `DexEngine`
  (STRUCTURE validation); the legacy `-error.zip` dump is preserved.
- `d2j-agent` — `JobManager` (per-job `SharedFlow`, multi-subscriber, TTL);
  `AgentBridgeServer` streams the flow as SSE; `Dex2JarTool` is engine-backed
  with progress + cancellation.

### Phase 7 — Java 17 bytecode target (this pass)
- **The whole build now emits Java 17 bytecode.** `build.gradle.kts` sets
  `sourceCompatibility`/`targetCompatibility = 17`, `options.release = 17` for
  every `JavaCompile`, and `jvmTarget = JVM_17` + `-Xjdk-release=17` for every
  `KotlinCompile`. The build still *runs* on the Gradle JVM (21); `--release`/
  `-Xjdk-release` make a stray JDK 18+ API a build failure instead of a runtime
  `UnsupportedClassVersionError` in a consumer.
- **UTF-8 is pinned** in the generated launchers (`sh_template`,
  `d2j_invoke.sh`, `d2j_invoke.bat`, and `applicationDefaultJvmArgs` for
  `dex-tools`/`d2j-agent`): JDK 17 inherits the OS locale charset (ASCII under a
  C/POSIX locale) whereas JDK 18+ forces UTF-8; pinning makes both behave alike.

### Tests
- `dex-translator/.../DexEngineTest.kt` — engine suite (DEX 041 standalone +
  header parse + non-zero `header_offset` rejection, COMPUTE_FRAMES validity,
  parallel-vs-sequential byte equality, fixed-timestamp determinism, limits,
  cancellation, validation tiers, concurrent-conversion isolation) plus
  **`annotationValuesSurviveSmaliToJarRoundTrip`** (this pass): a differential
  smali → dex → jar test asserting that an annotation's enum constant, array,
  nested annotation, class literal and boolean all survive with their *types*
  intact (ASM-inspected) and that the generated class passes BYTECODE validation.

---

## ✅ Verification State (2026-10-11)

| Check | Result |
|---|---|
| `./gradlew classes testClasses` (Java 17 target) | **BUILD SUCCESSFUL** |
| Bytecode version of every class in the distribution | **≤ major 61 (Java 17)**; project classes exactly 61 |
| `d2j-dex2jar` on `i_jetty.dex` under a **real JDK 17 JVM** | exit 0 → 470 classes, `javap` output correct |
| `d2j-agent` launcher under a real JDK 17 JVM | prints usage (runs) |
| Determinism (fresh JVM, separate processes, same JDK) | byte-identical jars |
| `annotationValuesSurviveSmaliToJarRoundTrip` | PASSED |

**Determinism root cause (investigated).** `-XX:+UnlockExperimentalVMOptions
-XX:hashCode=2` makes output byte-identical even for repeated in-JVM
conversions, while the default and `-XX:hashCode=3` do not. That proves the
residual variance comes from legacy IR code that iterates `HashSet`/`HashMap`
collections keyed by objects with *identity* `hashCode` (`Local`, `Stmt`,
`Reg`), not from the engine. It only affects register/local **numbering**; the
bytecode passes ASM bytecode validation either way. A fresh JVM process is
reproducible; repeated in-process conversions and different JDK versions can
renumber locals. Documented as a known limitation rather than "fixed" —
reworking every legacy transformer's collection type is a separate, larger
effort with no conversion-correctness payoff.

Full suite on the Java 17 target: **777 tests, 0 failures, 0 errors, 1 skip**
(`:dex-translator` 703 incl. the new annotation test; `:dex-ir` 48; `:d2j-agent` 9;
`:dex-tools` 5; `:dex-reader` 4; `:dex-writer` 4; `:d2j-smali` 3; `:d2j-jasmin` 1).
The single skip is the legacy abstract base class `TestDexClassV` (no test
methods of its own).

---

## 🚩 Known Honest Limitations

- **DEX 041 multi-entry containers** are rejected (standalone 041 is fully
  supported). Multi-entry containers are an *experimental, ART-documented
  non-production* format that does not appear inside APKs; a container entry's
  offsets are container-relative and would need a full relink to read standalone.
- **Byte-identity** holds for a fresh JVM process on a given JDK, but not for
  repeated conversions inside one long-lived JVM, nor across JDK versions
  (cosmetic register renumbering; verifier-clean in all cases). Root cause and
  evidence above.
- **CLI memory profile**: the jar is built in memory before the atomic publish.
- **Bytecode validation** is ASM `CheckClassAdapter` (structural), not full JVM
  verification (which needs a classpath for the type hierarchy).
- **Annotation values**: correct end-to-end (differential test); enum constants
  are internally represented as an owner-less `Field` rather than a dedicated
  node type — an internal model detail, not a conversion bug.

---

## File inventory (this pass)

```
build.gradle.kts                                            (Java 17 target)
dex-translator/build.gradle.kts                             (test dep: dex-writer)
dex-tools/build.gradle.kts                                  (UTF-8 jvm arg)
d2j-agent/build.gradle.kts                                  (UTF-8 jvm arg)
dex-tools/src/main/bin_gen/sh_template|d2j_invoke.sh|.bat    (UTF-8 jvm arg)
dex-translator/src/test/kotlin/.../DexEngineTest.kt         (+ annotation round-trip test)
README.md, WORKLOG.md                                       (docs)
```
