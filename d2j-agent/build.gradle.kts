description = "AI Agent Bridge (HTTP/JSON tool API + real-time progress)"

plugins {
    application
}

dependencies {
    implementation(project(":dex-translator"))
    implementation(project(":dex-reader"))
    implementation(project(":dex-reader-api"))
    implementation(project(":dex-writer"))
    implementation(project(":d2j-smali"))
    implementation(project(":d2j-base-cmd"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.kotlin)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.engine)
}

application {
    mainClass.set("riad.codes.agent.AgentBridgeCliKt")
}
