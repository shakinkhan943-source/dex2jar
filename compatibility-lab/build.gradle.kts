plugins {
    java
}

repositories {
    mavenCentral()
    google()
}

// Intentionally isolated from the root build: this directory is a standalone Gradle build.
// Dynamic versions are refreshed in CI and the exact resolved versions are recorded.
val upstreamDexlib by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val ksmaliDexlib by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    upstreamDexlib("org.smali:dexlib2:2.+")
    // Newer community fork; tested independently to avoid duplicate org.jf.dexlib2 classes.
    ksmaliDexlib("io.github.shakarover.ksmali:smali-dexlib2:5.+")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.register<JavaExec>("probeUpstreamDexlib") {
    group = "verification"
    description = "Probe DEX inputs using the newest available org.smali:dexlib2 2.x release."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath + upstreamDexlib
    mainClass.set("lab.DexParserProbe")
    args("org.smali:dexlib2", upstreamDexlib.asPath)
    systemProperty("lab.parser", "org.smali:dexlib2")
}

tasks.register<JavaExec>("probeKsmaliDexlib") {
    group = "verification"
    description = "Probe DEX inputs using the newest available ksmali dexlib fork."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath + ksmaliDexlib
    mainClass.set("lab.DexParserProbe")
    args("io.github.shakarover.ksmali:smali-dexlib2", ksmaliDexlib.asPath)
    systemProperty("lab.parser", "io.github.shakarover.ksmali:smali-dexlib2")
}

tasks.register("printResolvedParserVersions") {
    group = "verification"
    doLast {
        println("UPSTREAM_DEXLIB=" + upstreamDexlib.resolvedConfiguration.resolvedArtifacts.joinToString { "${it.moduleVersion.id}" })
        println("KSMALI_DEXLIB=" + ksmaliDexlib.resolvedConfiguration.resolvedArtifacts.joinToString { "${it.moduleVersion.id}" })
    }
}

tasks.register("verifyDexCompatibility") {
    group = "verification"
    description = "Resolve dynamic parser versions and run both independent parser probes."
    dependsOn("printResolvedParserVersions", "probeUpstreamDexlib", "probeKsmaliDexlib")
}
