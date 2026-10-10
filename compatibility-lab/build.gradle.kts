plugins {
    java
}

repositories {
    mavenCentral()
    google()
}

// Standalone test only: no parser dependency is added to the production build.
// Keep the known-working upstream implementation as the compatibility gate.
val upstreamDexlib by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(upstreamDexlib.name, "org.smali:dexlib2:2.+")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.register<JavaExec>("probeUpstreamDexlib") {
    group = "verification"
    description = "Probe DEX fixtures using the newest available org.smali:dexlib2 2.x release."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath + upstreamDexlib
    mainClass.set("lab.DexParserProbe")
    args("org.smali:dexlib2")
    systemProperty("lab.parser", "org.smali:dexlib2")
}

tasks.register("printResolvedParserVersions") {
    group = "verification"
    doLast {
        println("UPSTREAM_DEXLIB=" + upstreamDexlib.resolvedConfiguration.resolvedArtifacts.joinToString { "${it.moduleVersion.id}" })
    }
}

tasks.register("verifyDexCompatibility") {
    group = "verification"
    description = "Resolve and probe the upstream dexlib2 parser against all available DEX fixtures."
    dependsOn("printResolvedParserVersions", "probeUpstreamDexlib")
}
