description = "Dex Reader"

dependencies {
    api(project(":dex-reader-api"))
    implementation(libs.kotlin.stdlib)
    testImplementation(libs.junit.api)
    testImplementation(libs.commons.compress)
    testRuntimeOnly(libs.junit.engine)
}
