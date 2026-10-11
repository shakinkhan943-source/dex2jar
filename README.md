# dex2jar (Kotlin / 2026 modernized)

Tools to work with Android `.dex` and Java `.class` files: convert `.dex`/`.apk`
to `.jar`, disassemble to smali, and drive everything from an AI agent over
a local HTTP bridge with real-time progress.

> Project originally moved to [GitHub: pxb1988/dex2jar](https://github.com/pxb1988/dex2jar).
> This fork modernizes the build, packages, and adds a Kotlin agent layer.

## Download / JitPack

The root JitPack artifact is a **single self-contained executable JAR**. It
contains the dex2jar CLI, project modules, and runtime libraries. The previous
root project did not produce a JAR; only `dex-tools`' multi-file ZIP
distribution existed, which is not the same as a complete standalone JAR.

Add JitPack to repositories:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
```

Then add the dependency:

```kotlin
dependencies {
    implementation("com.github.shakinkhan943-source:dex2jar:3.0")
}
```

For a direct download, use the root artifact URL:

```text
https://jitpack.io/com/github/shakinkhan943-source/dex2jar/3.0/dex2jar-3.0.jar
```

Run it on **JDK 17 or newer**:

```bash
java -jar dex2jar-3.0.jar --help
```

The JAR is a JVM command-line tool, not an Android/Dalvik-compatible library
for loading directly inside an Android app. For Android integration, use the
module APIs and verify that the required runtime features are supported by the
app's runtime; the standalone CLI itself expects a JVM.

## What changed in this modernization

| Area | Before | Now |
|------|--------|-----|
| Build | Gradle 6.9.1 (Groovy), `compile`/`maven` plugins | **Gradle 9.8.1**, Kotlin DSL, version catalog |
| Language target | Java 8 | **JVM 17 bytecode** |
| Kotlin toolchain | — | **Kotlin 2.4.21** Gradle plugin |
| Bytecode lib | ASM 9.5 | **ASM 9.11** |
| Tests | JUnit 4 only | **JUnit 6.1.3** + Vintage bridge for legacy tests |
| Packages | `com.googlecode.*` | **`riad.codes.*`** |
| New capability | — | **`d2j-agent`** — HTTP/JSON tool API + coroutines progress streaming |
| Packaging | Root JAR missing; CLI ZIP distribution only | **Root fat JAR** for JitPack plus CLI ZIP distribution |

## Module layout

```
dex-reader-api   public reader API (visitors, nodes, constants)
dex-reader       dex binary parser (DexFileReader)
dex-writer       dex binary writer
dex-ir           intermediate representation + optimizers
dex-translator   dex -> IR -> ASM class pipeline
dex-tools        command line tools (d2j-dex2jar, d2j-baksmali, ...)
d2j-smali        smali/baksmali (ANTLR4)
d2j-jasmin       jasmin assembler/disassembler (ANTLR3)
d2j-base-cmd     shared CLI base
d2j-agent        AI agent bridge (HTTP/JSON + real-time progress)
```

## Requirements

- **JDK 17+ to run the standalone CLI JAR.**
- All project modules target Java 17 bytecode.
- Gradle resolves the runtime libraries during the build; the root fat JAR
  embeds those runtime classes so the CLI download does not need sidecar JARs.

## Build

```bash
./gradlew assemble test
./gradlew :dex-tools:distZip
```

Artifacts:
- Self-contained JitPack-compatible root JAR: `build/libs/dex2jar-3.0.jar`
- Multi-file CLI distribution: `dex-tools/build/distributions/dex-tools.zip`

To inspect the root artifact locally:

```bash
unzip -p build/libs/dex2jar-3.0.jar META-INF/MANIFEST.MF
jar tf build/libs/dex2jar-3.0.jar | grep -E 'Dex2jarCmd.class|org/objectweb/asm/ClassReader.class|kotlin/Unit.class'
```

The distribution ZIP contains launcher scripts and a `lib/` directory. It is
useful when you want the full set of `d2j-*` commands; the root fat JAR is the
single-file option for the main `d2j-dex2jar` CLI.

## Command line tools

```bash
d2j-dex2jar -f app.apk
d2j-baksmali -o out-smali app.dex
d2j-smali -o out.dex out-smali
```

## AI agent bridge (`d2j-agent`)

`d2j-agent` turns the tools into a local HTTP service an agent can discover and
call. Every run streams real-time progress.

### Start the bridge

```bash
./gradlew :d2j-agent:run --args="--host 127.0.0.1 --port 8787"
./gradlew :d2j-agent:distZip
```

Options include `--host`, `--port`, and `--progress-url`. The bridge exposes
`/v1/health`, `/v1/tools`, `/v1/tools/{name}`, and job/progress endpoints.

## Programmatic Kotlin API

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

## Known limitations

- **DEX 041 multi-entry containers.** Standalone DEX 041 files (`header_offset == 0`)
  are supported. A non-zero `header_offset` entry is rejected with an actionable
  message because its offsets are container-relative and require relinking.
- **Byte identity.** A fresh JVM produces deterministic output for a given input
  and JDK, but legacy identity-hash-keyed collections can vary register/local
  numbering across in-process repeats and JDK versions.
- **CLI memory profile.** The conversion engine builds the JAR in memory before
  atomic publish, increasing peak memory on very large inputs.
- **Bytecode validation** uses ASM `CheckClassAdapter`, not full JVM verification.

## License

[Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
