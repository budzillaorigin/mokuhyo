plugins {
    // Versions come from the root build, which already puts the Kotlin Gradle plugin on the classpath.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("app.tsumugi.server.MainKt")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.sqlite.jdbc)
    implementation(libs.password4j)
    implementation(libs.webauthn4j.core)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.webauthn4j.test)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}
