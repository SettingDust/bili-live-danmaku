import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    kotlin("jvm") version "1.5.21"
    id("org.jlleitschuh.gradle.ktlint") version "10.1.0"
    `maven-publish`
}

group = "io.github.settingdust.bilive"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "1.6.2"

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    val brotliVersion = "1.5.0"
    implementation("com.aayushatharva.brotli4j:brotli4j:$brotliVersion")
    implementation("com.aayushatharva.brotli4j:native-windows-x86_64:$brotliVersion")

    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.0-rc1")

    testImplementation(kotlin("test-junit5"))
}

configure<KtlintExtension> {
}

tasks {
    test {
        useJUnitPlatform()
    }
}
