description = "Dex Translator"

dependencies {
    implementation(project(":dex-reader"))
    implementation(project(":dex-reader-api"))
    implementation(project(":dex-ir"))
    implementation(project(":d2j-base-cmd"))
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
    implementation(libs.asm.util)
    implementation(libs.asm.analysis)
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    testImplementation(project(":d2j-smali"))
    testImplementation(project(":d2j-jasmin"))
    // Annotation round-trip tests assemble smali into a dex via DexFileWriter.
    testImplementation(project(":dex-writer"))
    // Legacy tests use the bundled Android `dx` tool to build dex fixtures.
    testImplementation(files("libs/dx-30.0.2.jar"))
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
}
