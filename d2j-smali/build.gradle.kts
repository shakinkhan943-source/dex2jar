description = "Smali/Baksmali"

plugins {
    id("antlr")
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
