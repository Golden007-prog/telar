plugins {
    id("telar.jvm-library")
    id("telar.publishing")
    // The tests save a @Serializable state.
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":telar-core"))
    implementation(project(":telar-serialization"))
    // Only the javax.sql API of the JDK: the app brings the JDBC driver of PostgreSQL and its pool.

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.postgresql.jdbc)
    // A real PostgreSQL for the tests, started from binaries in a jar: no Docker, so the tests also
    // run on the macOS runner of the release. It brings the binaries of Linux and Windows on x86-64;
    // those of macOS on Apple silicon are added here.
    testImplementation(libs.embedded.postgres)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testRuntimeOnly(libs.embedded.postgres.binaries.darwin.arm64)
}
