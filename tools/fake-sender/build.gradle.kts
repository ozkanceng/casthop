// Tahtasız/tabletsiz test için gönderici: H.264 Annex-B dosyasını protokol üzerinden gerçek zaman hızında yollar.
// android/ derlemesine dahildir:  ./gradlew :fake-sender:installDist
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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

application {
    applicationName = "fake-sender"
    mainClass.set("io.github.ozkanceng.yansit.tools.fakesender.MainKt")
    // Konteynerde yerel ayar C olsa da Türkçe çıktı bozulmasın.
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    implementation(project(":sender"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
