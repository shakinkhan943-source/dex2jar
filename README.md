# dex2jar (Kotlin / 2026 modernized)

Tools to work with Android `.dex` and Java `.class` files: convert `.dex`/`.apk`
to `.jar`, disassemble to smali, and now **drive everything from an AI agent over
a local HTTP bridge with real-time progress**.

> Project originally moved to [GitHub: pxb1988/dex2jar](https://github.com/pxb1988/dex2jar).
> This fork modernizes the build, packages, and adds a Kotlin agent layer.

---

## What changed in this modernization

| Area | Before | Now |
|------|--------|-----|
| Build | Gradle 6.9.1 (Groovy), `compile`/`maven` plugins | **Gradle 9.8.1**, Kotlin DSL, version catalog |
| Language target | Java 8 | **JVM 21** (LTS) |
| Kotlin toolchain | — | **Kotlin 2.4.21** Gradle plugin |
| Bytecode lib | ASM 9.5 | **ASM 9.11** |
| Tests | JUnit 4 only | **JUnit 6.1.3** (Jupiter) + Vintage bridge for legacy tests |
| Packages | `com.googlecode.*` | **`riad.codes.*`** |
| New capability | — | **`d2j-agent`** — HTTP/JSON tool API + coroutines progress streaming |
| Removed | JDK-internal `sun.security.*` APK signer | self-contained signer (`ApkSign` still works) |
| **Dex version support** | DEX 035–040 only | DEX **035, 037–041 supported** — DEX **041** (Android 15) 0x78-byte container header is parsed for standalone files (the layout inside an APK's `classes*.dex`); a multi-entry container slice (non-zero `header_offset`) is rejected with an actionable error |

---

## Module layout

```
dex-reader-api   public reader API (visitors, nodes, constants)
dex-reader       dex binary parser (DexFileReader)
dex-writer       dex binary writer
dex-ir           intermediate representation + optimizers (IR AST, SSA, dead-code, etc.)
dex-translator   dex -> IR -> ASM class pipeline (Dex2jar, Dex2Asm, IR2JConverter)
dex-tools        command line tools (d2j-dex2jar, d2j-baksmali, ...)
d2j-smali        smali/baksmali (ANTLR4)
d2j-jasmin       jasmin assembler/disassembler (ANTLR3)
d2j-base-cmd     shared CLI base
d2j-agent        AI agent bridge (HTTP/JSON + real-time progress)   <-- new
```

## Requirements

* JDK 21+
* No external runtime dependencies beyond the Gradle-resolved libraries.

## Build

```bash
./gradlew assemble          # compile + jar everything
./gradlew test              # run tests
./gradlew :dex-tools:distZip   # build the CLI distribution
```

The distribution is written to `dex-tools/build/distributions/dex-tools.zip`.
Unzip it and run the `d2j-*` launcher scripts from the unzipped directory root
(they load their jars from `lib/`):

```bash
cd dex-tools
sh d2j-dex2jar.sh -f ~/path/to/app.apk
```

---

## Command line tools

```bash
# convert a dex/apk to a jar of .class files
d2j-dex2jar -f app.apk

# disassemble a dex to smali
d2j-baksmali -o out-smali app.dex

# assemble smali back to a dex
d2j-smali -o out.dex out-smali
```

---

## AI agent bridge (`d2j-agent`)

`d2j-agent` turns the tools into a local HTTP service an agent can discover and
call. Every run streams real-time progress.

### Start the bridge

```bash
# run straight from source
./gradlew :d2j-agent:run --args="--host 127.0.0.1 --port 8787"

# or build the standalone launcher
./gradlew :d2j-agent:distZip
unzip -q d2j-agent/build/distributions/d2j-agent.zip -d /tmp/d2j-agent
/tmp/d2j-agent/d2j-agent/bin/d2j-agent --host 127.0.0.1 --port 8787
```

Options:

| Flag | Default | Meaning |
|------|---------|---------|
| `--host` | `127.0.0.1` | Bind address |
| `--port` | `8787` | **Agent connect port** |
| `--progress-url` | – | POST every progress event to this http(s) URL |

### HTTP API

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/v1/health` | Status, version, exposed tool names |
| `GET`  | `/v1/tools` | Machine-readable tool catalogue (JSON Schema-like) |
| `POST` | `/v1/tools/{name}` | Run a tool, return a `ToolResult` as JSON |
| `POST` | `/v1/tools/{name}/stream` | Run a tool, stream progress as **Server-Sent Events** |
| `GET`  | `/v1/jobs` | List known job ids |
| `GET`  | `/v1/jobs/{id}` | Fetch a job result (`202` while running) |
| `GET`  | `/v1/jobs/{id}/events` | Stream a job's progress as SSE |

#### Tools

| Name | Args |
|------|------|
| `dex2jar` | `input` (req), `output`, `debugInfo`, `reuseReg`, `skipExceptions` |
| `baksmali` | `input` (req), `outputDir`, `noDebug`, `useLocals`, `noParameterRegisters` |
| `dexInfo` | `input` (req) |

#### Example: discover tools

```bash
curl http://127.0.0.1:8787/v1/tools
```

#### Example: run `dexInfo`

```bash
curl -X POST http://127.0.0.1:8787/v1/tools/dexInfo \
     -H 'Content-Type: application/json' \
     -d '{"input":"/path/to/app.dex"}'
```

```json
{
  "jobId": "…",
  "tool": "dexInfo",
  "success": true,
  "output": { "classCount": 42, "classes": ["Lcom/x/A;", "…"] },
  "durationMs": 12
}
```

#### Example: run `dex2jar` (convert to a jar)

```bash
curl -X POST http://127.0.0.1:8787/v1/tools/dex2jar \
     -H 'Content-Type: application/json' \
     -d '{"input":"/path/to/app.apk","output":"/tmp/app.jar","reuseReg":true}'
```

#### Example: run `baksmali` (disassemble to smali)

```bash
curl -X POST http://127.0.0.1:8787/v1/tools/baksmali \
     -H 'Content-Type: application/json' \
     -d '{"input":"/path/to/app.dex","outputDir":"/tmp/out-smali"}'
```

#### Example: stream progress (SSE)

```bash
curl -N -X POST http://127.0.0.1:8787/v1/tools/dex2jar/stream \
     -H 'Content-Type: application/json' \
     -d '{"input":"/path/to/app.apk","output":"/tmp/app.jar"}'
```

Each SSE frame is one JSON object:

```
data: {"type":"job","jobId":"…","tool":"dex2jar"}
data: {"type":"progress","event":{"phase":"STARTED","message":"converting app.apk -> app.jar","percent":0.0,"…":…}}
data: {"type":"progress","event":{"phase":"PROGRESS","message":"converting… (500 ms)"}}
data: {"type":"progress","event":{"phase":"ITEM","message":"com/x/A","current":1,"total":42,"percent":2.38}}
data: {"type":"result","result":{"success":true,"output":{"classCount":42,…}}}
```

`ProgressEvent` fields:

| Field | Type | Notes |
|-------|------|-------|
| `jobId` | string | Stable per run |
| `tool` | string | Tool name |
| `phase` | enum | `STARTED`, `PROGRESS`, `ITEM`, `COMPLETED`, `FAILED` |
| `message` | string | Human-readable |
| `percent` | number? | 0–100 when known |
| `current` / `total` | number? | For per-item phases |
| `timestamp` | string | ISO-8601 |
| `data` | object | Free-form payload |

### Real-time progress send API

Pass `--progress-url https://your-collector/ingest` and **every** event is also
POSTed there as `application/json` (same `ProgressEvent` schema). Point any
dashboard, log pipeline, or agent orchestrator at it.

---

## Programmatic (Kotlin) API

```kotlin
import riad.codes.agent.*
import riad.codes.agent.tools.Dex2JarTool
import kotlinx.coroutines.runBlocking

runBlocking {
    val tool = Dex2JarTool()
    val events = mutableListOf<ProgressEvent>()
    val reporter = ProgressReporter("job-1", tool.spec.name,
        listOf(ProgressListener { events.add(it) }))

    val result: ToolResult = tool.run(
        ToolArguments(mapOf("input" to "app.apk", "output" to "app.jar")),
        reporter,
    )
    println("classes: ${result.output["classCount"]}")
}
```

For a full server, compose `ToolRegistry`, `JobManager`, and `AgentBridgeServer`:

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val jobs = JobManager(scope)                     // add sinks for remote progress
val server = AgentBridgeServer("127.0.0.1", 8787, ToolRegistry.defaults(), scope, jobs)
server.start()
```

### Conversion engine (`DexEngine`) — recommended

The modern facade is `riad.codes.d2j.engine.DexEngine`: one call converts a
`.dex`/`.apk`/`.zip` into a validated jar with atomic publish, input limits,
per-class progress, cancellation, and a structured report. The `d2j-dex2jar`
CLI and the agent's `dex2jar` tool route through it.

```kotlin
import riad.codes.d2j.engine.DexEngine
import riad.codes.d2j.engine.ConversionOptions

val report = DexEngine.convert(
    data = File("app.dex").readBytes(),
    output = Path.of("app.jar"),
    options = ConversionOptions(reuseReg = true),
)
println("${report.classCount} classes, dex ${report.dexVersion}")
```

Guarantees: the destination is only touched at the end via an atomic move (a
failed or cancelled run never corrupts an existing jar); staging is always
cleaned up; recoverable translate errors surface in `report.warnings` (or fail
the run with `strict = true`); identical inputs produce byte-identical jars.

Multidex containers are translated **per-DEX on a bounded worker pool** (first-
DEX-wins dedup; output is byte-identical to the sequential run). Override the
worker count with `ConversionOptions(parallelism = N)` (`0` = auto, `1` =
sequential). `ConversionOptions(computeFrames = true)` opts into ASM stack-map
frame computation with a safe fallback for Android classes.

### Lower-level conversion API

```kotlin
import riad.codes.d2j.dex.Dex2jar

Dex2jar.from(File("app.dex")).topoLogicalSort().to(Path.of("app.jar"))
```

---

## Known limitations

- **DEX 041 multi-entry containers.** Standalone DEX 041 files (the layout inside
  an APK) are fully supported. A DEX 041 entry with a non-zero `header_offset`
  (a slice of a multi-entry container) is rejected with an actionable message;
  extract the individual entry first.
- **CLI memory profile.** The engine builds the jar in memory before the atomic
  publish (no partial output, but higher peak RSS than the legacy per-class
  write on very large inputs).
- **Bytecode validation** uses ASM `CheckClassAdapter` (structural data-flow),
  not full JVM verification (type hierarchy needs a classpath).

---

## License

[Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
