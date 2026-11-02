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
    testRuntimeOnly(libs.junit.platform.launcher)
}

lint {
    abortOnError = true
    disable += setOf("GradleDependency", "NewerVersionAvailable")
}

tasks.test {
    useJUnitPlatform()
    // Ortak test vektörleri depo kökündeki protocol/ dizininde.
    systemProperty("yansit.vectors", rootProject.file("../protocol/vectors").absolutePath)
}
