description = "Dex Writer"

dependencies {
    api(project(":dex-reader-api"))
    implementation(libs.kotlin.stdlib)
    testImplementation(project(":dex-reader"))
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
}
