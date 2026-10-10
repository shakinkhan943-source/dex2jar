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
| **Dex version support** | DEX 035–040 only | DEX **035–048** recognized (041 flagged experimental; `MAX_KNOWN_VERSION` vs `MAX_SUPPORTED_VERSION` split) |

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

### Lower-level conversion API

```kotlin
import riad.codes.d2j.dex.Dex2jar

Dex2jar.from(File("app.dex")).topoLogicalSort().to(Path.of("app.jar"))
```

---

## License

[Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
