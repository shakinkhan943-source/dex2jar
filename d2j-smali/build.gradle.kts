description = "Smali/Baksmali"

plugins {
    id("antlr")
}

// Gradle's ANTLR plugin makes the "api" configuration inherit from "antlr".
// That leaks the ANTLR generator and its ICU/StringTemplate tool dependencies
// onto application runtime classpaths. The runtime is declared separately below,
// so keep the generator configuration isolated to grammar generation.
configurations.named("api") {
    setExtendsFrom(extendsFrom.filterNot { it.name == "antlr" })
}

dependencies {
    implementation(project(":dex-reader"))
    implementation(project(":dex-writer"))
    implementation(project(":d2j-base-cmd"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.antlr4.runtime)
    antlr(libs.antlr4)
    testImplementation(libs.smali.baksmali)
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
}

tasks.named<AntlrTask>("generateGrammarSource").configure {
    arguments.addAll(listOf(
        "-no-listener",
        "-visitor",
        "-package",
        "riad.codes.d2j.smali.antlr4",
    ))
}

sourceSets {
    named("main") {
        antlr.setSrcDirs(listOf("src/main/antlr4"))
    }
    named("test") {
        resources.srcDirs(layout.buildDirectory.dir("classes/test").get().asFile)
    }
}
