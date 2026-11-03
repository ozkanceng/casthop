// Gönderici çekirdeği: yayın planlama, gönderim kuyruğu, oturum (saf Kotlin/JVM, Android bağımlılığı yok).
// :app MediaCodec ile, tools/fake-sender dosyadan okuyan bir VideoSource ile kullanır; böylece uygulamanın
// ağ mantığı CI'da gerçek alıcıya karşı test edilir.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
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
    disable += setOf("GradleDependency", "NewerVersionAvailable")
}

tasks.test {
    useJUnitPlatform()
}
