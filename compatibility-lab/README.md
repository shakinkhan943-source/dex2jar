# Isolated DEX Compatibility Lab

This directory is a **separate Gradle build**. It is intentionally not included by the repository's root settings.gradle.kts, and its dependencies do not become part of dex2jar's CLI, agent, or runtime artifacts.

## What it probes

- org.smali:dexlib2:2.+: newest published 2.x parser resolved by Gradle at run time.
- io.github.shakarover.ksmali:smali-dexlib2:5.+: newest published 5.x fork resolved independently.
- Prints resolved artifacts and inspects each fixture's actual dex magic before asking the selected parser to load it and enumerate classes.
- Runs in a separate GitHub Actions workflow: **DEX Compatibility Lab**.

Dynamic versions are deliberate here because this is a compatibility experiment, not a production dependency declaration. CI uses --refresh-dependencies; reports should always be read alongside the exact resolved version numbers. A newly published release can change results, so a failure is useful signal rather than a reason to silently change the main tools dependencies.

## Run locally

From the repository root, with JDK 21 available:

```bash
./gradlew --no-daemon -p compatibility-lab --refresh-dependencies printResolvedParserVersions
./gradlew --no-daemon -p compatibility-lab --refresh-dependencies probeUpstreamDexlib
./gradlew --no-daemon -p compatibility-lab --refresh-dependencies probeKsmaliDexlib
```

By default, the probe reads tracked regression inputs from dex-translator/src/test/resources/dexes. To supply an independent corpus, put valid .dex files in compatibility-lab/fixtures/ (or set DEX_FIXTURE_DIR to another directory). Do not use a filename as evidence of its version; the report reads the header magic.

## Important DEX 041 caveat

DEX 041 is a container-format change, not merely a version number. A valid test must exercise the 0x78-byte header and container fields such as container_size and header_offset, ideally including multiple logical DEX files in one physical container. A normal DEX 039 file renamed to dex041.dex is not a test. Android's official format documentation describes DEX 041 support as experimental, so treat it as experimental even if a parser accepts a fixture.

## What a PASS means

A PASS means the selected parser opened a fixture and enumerated its classes. It does **not** prove dex2jar can translate every instruction, produce semantically valid JVM bytecode, handle every container layout, or integrate safely into DrApk. Follow parser probing with actual engine conversion, class-file verification, real-world APK samples, malformed-input/resource-limit tests, and a separate DEX 041 container corpus.
