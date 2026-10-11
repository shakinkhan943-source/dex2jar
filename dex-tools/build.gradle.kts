description = "Dex Tools"

plugins {
    application
}

dependencies {
    implementation(project(":dex-translator"))
    implementation(project(":dex-ir"))
    implementation(project(":dex-reader"))
    implementation(project(":dex-reader-api"))
    implementation(project(":d2j-smali"))
    implementation(project(":d2j-jasmin"))
    implementation(project(":dex-writer"))
    implementation(project(":d2j-base-cmd"))
    implementation(libs.asm.tree)
    implementation(libs.asm.util)
    implementation(libs.asm.commons)
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.kotlin)
    implementation(libs.slf4j.simple)
}

application {
    mainClass.set("riad.codes.dex2jar.tools.Dex2jarCmd")
    // JDK 17 inherits the OS locale's default charset (ASCII under a POSIX/C
    // locale), while JDK 18+ forces UTF-8. Pin UTF-8 so file-name and text
    // handling behaves the same on every supported JDK. Overridable by the user.
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

/**
 * Generates the `d2j-*` launcher scripts (from src/main/bin_gen) into
 * build/generated-sources/bin. The generated scripts are copied to the root of
 * the distribution and locate their jars under `lib/`.
 */
val binGen = tasks.register<JavaExec>("binGen") {
    group = "build"
    description = "Generate the d2j-* launcher scripts"
    dependsOn(tasks.named("jar"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("riad.codes.dex2jar.bin_gen.BinGen")

    val cfgDir = layout.projectDirectory.dir("src/main/bin_gen")
    val binDir = layout.buildDirectory.dir("generated-sources/bin")
    inputs.dir(cfgDir)
    outputs.dir(binDir)

    args(cfgDir.asFile.absolutePath, binDir.get().asFile.absolutePath)
}

distributions {
    main {
        contents {
            // Generated d2j-dex2jar.sh / .bat ... live at the distribution root.
            from(binGen)
            from(layout.projectDirectory.file("open-source-license.txt")) { into("lib") }
            from(rootProject.file("NOTICE.txt")) { into("lib") }
            from(rootProject.file("LICENSE.txt")) { into("lib") }
        }
    }
}

tasks.named("startScripts").configure {
    dependsOn(binGen)
}
