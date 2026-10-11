import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

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

repositories {
    mavenCentral()
    google()
}

group = "riad.codes.d2j"
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
 * The old root project had no Java component/JAR at all. JitPack therefore
 * could not serve the expected single artifact, while dex-tools only produced
 * a multi-file ZIP distribution. This task bundles dex-tools and every runtime
 * dependency into the root JAR so downloaders receive one complete executable.
 */
tasks.named<Jar>("jar") {
    archiveBaseName.set("dex2jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "riad.codes.dex2jar.tools.Dex2jarCmd"
        attributes["Implementation-Title"] = "dex2jar"
        attributes["Implementation-Version"] = project.version
    }
    // Add every module's compiled classes/resources directly. Avoid unpacking the
    // subprojects' generated JARs, which would create implicit task dependencies
    // rejected by recent Gradle versions.
    from(subprojects.map { subproject ->
        subproject.extensions.getByType<SourceSetContainer>().named("main").get().output
    })
    // Unpack only third-party modules; local project artifacts are already covered
    // by the source-set outputs above.
    from({
        project(":dex-tools").configurations.getByName("runtimeClasspath")
            .incoming.artifactView {
                componentFilter { it is ModuleComponentIdentifier }
            }
            .files
            .map { zipTree(it) }
    })
    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/INDEX.LIST",
        "module-info.class",
    )
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "riad.codes.d2j"
            artifactId = "dex2jar"
        }
    }
}
