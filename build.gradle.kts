import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `maven-publish`
    alias(libs.plugins.kotlin.jvm) apply false
}

/**
 * Bytecode target for every module. The libraries are consumed as ordinary
 * Gradle/JVM dependencies by downstream projects, so the emitted class files
 * must be loadable by the *lowest* JDK a consumer is likely to run on. Java 17
 * is the modern LTS baseline (and the level Android tooling, ASM and the
 * bundled legacy `dx` toolchain all agree on), so everything is compiled with
 * `--release 17` / `-jvm-target 17` even though the build itself runs on the
 * Gradle JVM (currently 21). This keeps `UnsupportedClassVersionError` away
 * from consumers still on JDK 17.
 */
val javaBytecodeTarget = JavaVersion.VERSION_17

/** Kotlin `JvmTarget` mirror of [javaBytecodeTarget]. */
val javaBytecodeTargetJvm = JvmTarget.JVM_17

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "riad.codes.d2j"
version = "2.2.0"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")

    repositories {
        mavenCentral()
        google()
    }

    configure<JavaPluginExtension> {
        sourceCompatibility = javaBytecodeTarget
        targetCompatibility = javaBytecodeTarget
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        // `--release` pins both the language level and the JDK API surface to 17,
        // so a call to a JDK 18+ method fails the build instead of silently
        // producing a class file a JDK 17 JVM cannot link.
        options.release.set(javaBytecodeTarget.majorVersion.toInt())
        // Test fixtures must be dexifyable by the bundled legacy dx tool, which
        // cannot translate the Java 9+ `invokedynamic makeConcatWithConstants`
        // opcode (needs sdk>=26). Force javac to emit StringBuilder-based concat.
        if (name.lowercase().contains("test")) {
            options.compilerArgs.add("-XDstringConcat=inline")
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(javaBytecodeTargetJvm)
            // `-Xjdk-release` limits Kotlin to the JDK 17 API surface as well.
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }

    // Generated ANTLR sources are not javadoc-clean; keep the javadoc task from
    // breaking `assemble` while still allowing it to be run explicitly.
    tasks.matching { it.name == "javadoc" }.configureEach {
        enabled = false
    }

    dependencies {
        add("testImplementation", platform(libsCatalog.findLibrary("junit-bom").get()))
        add("testRuntimeOnly", libsCatalog.findLibrary("junit-launcher").get())
        // Legacy JUnit 4 tests still present in the engine modules run through the
        // JUnit Platform Vintage engine. New tests should target JUnit 5.
        add("testImplementation", libsCatalog.findLibrary("junit4").get())
        add("testRuntimeOnly", libsCatalog.findLibrary("junit-vintage").get())
    }

    tasks.named<Test>("test") {
        useJUnitPlatform()
        maxHeapSize = "512m"
        testLogging {
            events("passed", "failed", "skipped")
        }
    }

    publishing {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
                groupId = "riad.codes.d2j"
                artifactId = project.name
            }
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
