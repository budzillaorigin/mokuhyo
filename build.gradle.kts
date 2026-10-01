plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.mp) apply false
    alias(libs.plugins.sqldelight) apply false
}

// Dependency locking for what ships: the runtime classpaths. tools/gates/check_licenses.py diffs the lockfiles against
// docs/LICENSES.md and refuses GPL code on the app classpath (CLAUDE.md rule 6). Refresh with
// `./gradlew resolveAndLockAll --write-locks` after changing a dependency.
subprojects {
    configurations.matching { it.name == "runtimeClasspath" || it.name == "jvmRuntimeClasspath" }.configureEach {
        resolutionStrategy.activateDependencyLocking()
    }
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doFirst { require(gradle.startParameter.isWriteDependencyLocks) { "run with --write-locks" } }
        doLast {
            configurations.filter { it.isCanBeResolved && (it.name == "runtimeClasspath" || it.name == "jvmRuntimeClasspath") }
                .forEach { it.resolve() }
        }
    }
}
