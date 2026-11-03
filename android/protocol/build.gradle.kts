// Saf Kotlin/JVM: Android bağımlılığı yok. Hem :app hem tools/fake-sender kullanır.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

// Toolchain yerine hedef bayt kodu sabitlenir: herhangi bir JDK 17+ ile derlenir.
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
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}

lint {
    abortOnError = true
    // TrulyRandom yalnız Android ≤ 4.3 içindir; uygulamanın minSdk değeri 29.
    disable += setOf("GradleDependency", "NewerVersionAvailable", "TrulyRandom")
}

tasks.test {
    useJUnitPlatform()
    // Ortak test vektörleri depo kökündeki protocol/ dizininde.
    systemProperty("yansit.vectors", rootProject.file("../protocol/vectors").absolutePath)
}
