description = "Jasmin assembler/disassembler"

plugins {
    id("antlr")
}

dependencies {
    implementation(project(":d2j-base-cmd"))
    implementation(libs.asm.tree)
    implementation(libs.asm.util)
    implementation(libs.kotlin.stdlib)
    implementation("org.antlr:antlr-runtime:3.5.2") {
        exclude(group = "org.antlr", module = "stringtemplate")
    }
    antlr(libs.antlr3)
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
}

sourceSets {
    named("main") {
        antlr.setSrcDirs(listOf("src/main/antlr3"))
    }
}
