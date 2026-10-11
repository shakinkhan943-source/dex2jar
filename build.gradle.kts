import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm) apply false
}

/**
 * Bytecode target for every module. The libraries are consumed as ordinary
 * Gradle/JVM dependencies by downstream projects, so the emitted class files
 * must be loadable by the lowest JDK a consumer is likely to run on. Java 17
 * is the modern LTS baseline, so everything is compiled with --release 17 /
 * -jvm-target 17 and the build itself runs on JDK 17.
 */
val javaBytecodeTarget = JavaVersion.VERSION_17
val javaBytecodeTargetJvm = JvmTarget.JVM_17
val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Only libraries used by the executable CLI belong in the fat JAR.
 *
 * Do not derive this from dex-tools' complete runtimeClasspath: Gradle's
 * generated-parser/tooling graph can pull in the ANTLR generator, StringTemplate
 * and ICU4J. Those are build-time tools, not dex2jar runtime requirements.
 * Project classes are added separately below.
 */
val packagedRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val antlr3Runtime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(packagedRuntime.name, libsCatalog.findLibrary("kotlin-stdlib").get())
    add(packagedRuntime.name, libsCatalog.findLibrary("asm").get())
    add(packagedRuntime.name, libsCatalog.findLibrary("asm-commons").get())
    add(packagedRuntime.name, libsCatalog.findLibrary("asm-tree").get())
    add(packagedRuntime.name, libsCatalog.findLibrary("asm-util").get())
    add(packagedRuntime.name, libsCatalog.findLibrary("asm-analysis").get())
    add(packagedRuntime.name, libsCatalog.findLibrary("antlr4-runtime").get())
    // Jasmin's ANTLR 3 parser needs the runtime, not the ANTLR 3 generator.
    // Resolved on its own so the generator's transitive build-time tooling
    // (StringTemplate, ICU4J, ...) can never leak into the fat JAR.
    add(antlr3Runtime.name, "org.antlr:antlr-runtime:3.5.2") {
        isTransitive = false
    }
}

repositories {
    mavenCentral()
    google()
}

group = "com.github.shakinkhan943-source"
version = "3.0"

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
        options.release.set(javaBytecodeTarget.majorVersion.toInt())
        if (name.lowercase().contains("test")) {
            options.compilerArgs.add("-XDstringConcat=inline")
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(javaBytecodeTargetJvm)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.matching { it.name == "javadoc" }.configureEach {
        enabled = false
    }

    dependencies {
        add("testImplementation", platform(libsCatalog.findLibrary("junit-bom").get()))
        add("testRuntimeOnly", libsCatalog.findLibrary("junit-launcher").get())
        add("testImplementation", libsCatalog.findLibrary("junit4").get())
        add("testRuntimeOnly", libsCatalog.findLibrary("junit-vintage").get())
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

/**
 * Root artifact used by the simple JitPack coordinate:
 * com.github.shakinkhan943-source:dex2jar:3.0
 *
 * This self-contained executable bundles the project's main outputs and a
 * small allow-list of runtime libraries. Test classes, the HTTP/JSON agent,
 * parser generators and their build-time dependencies are intentionally omitted.
 */
tasks.named<Jar>("jar") {
    archiveBaseName.set("dex2jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "riad.codes.dex2jar.tools.Dex2jarCmd"
        attributes["Implementation-Title"] = "dex2jar"
        attributes["Implementation-Version"] = project.version
    }
    // Project source-set main outputs only; never include test outputs or d2j-agent.
    from(subprojects.filter { it.name != "d2j-agent" }.map { subproject ->
        subproject.extensions.getByType<SourceSetContainer>().named("main").get().output
    })
    // Bundle only the required parser runtimes, Kotlin stdlib, and ASM libraries.
    from({
        (packagedRuntime.resolve() + antlr3Runtime.resolve()).map { zipTree(it) }
    })
    // Belt-and-suspenders: never ship build-time generator tooling or other
    // non-runtime artifacts even if the dependency graph changes later.
    exclude(
        "org/antlr/v4/parse/**",
        "org/antlr/v4/analysis/**",
        "org/antlr/v4/misc/**",
        "org/antlr/v4/tool/**",
        "org/antlr/template/**",
        "com/ibm/icu/**",
        "**/*.g4",
        "**/antlr.main.g4",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/INDEX.LIST",
        "module-info.class",
        "META-INF/versions/**/module-info.class",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
    )
}

/**
 * JitPack publishes whatever the root `mavenJava` publication contains. The
 * plain `java` component would also attach the sources jar and, worse, pull
 * in Gradle module metadata that advertises transitive project dependencies
 * (riad.codes.d2j:dex-translator, :d2j-smali, ...). Those sub-artifacts are
 * not published on their own, so a consumer resolving them through JitPack
 * would trigger extra builds/downloads.
 *
 * This publication therefore ships EXACTLY ONE file: the self-contained fat
 * `dex2jar-3.0.jar` produced by the root Jar task above. The POM has no
 * <dependencies> section at all, so adding
 *     implementation("com.github.shakinkhan943-source:dex2jar:<ref>")
 * to your project downloads only that single ~4 MB jar - nothing else.
 */
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "com.github.shakinkhan943-source"
            artifactId = "dex2jar"
            // Intentionally NOT using `from(components["java"])`: no sources
            // jar, no .module metadata, no transitive dependency list.
            artifacts.clear()
            artifact(tasks.named("jar"))
        }
    }
}
