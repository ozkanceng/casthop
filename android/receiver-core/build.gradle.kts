// Receiver core: pairing, session, connections, reports and media flow rules (pure Kotlin/JVM, no Android API).
// android/tv plugs in MediaCodec and AndroidKeyStore, tools/fake-receiver a counting backend and a test identity,
// so the same receiver logic runs under tools/conformance without a device.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":protocol"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

lint {
    abortOnError = true
    // TrulyRandom only concerns Android <= 4.3; minSdk is 29.
    disable += setOf("GradleDependency", "NewerVersionAvailable", "TrulyRandom")
}

tasks.test {
    useJUnitPlatform()
}
