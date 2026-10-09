import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    `maven-publish`
    alias(libs.plugins.kotlin.jvm) apply false
}

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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        withSourcesJar()
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
