// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure JVM module: BIP39 + SeedBox crypto. No Android dependency -> `./gradlew :core:test` runs on any machine.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
}

// `make test-cross` (CrossTest): the directory where seedtag.py and the core swap random backups. Unset otherwise.
tasks.test {
    val cross = providers.environmentVariable("SEEDTAG_CROSS_DIR").orElse("")
    inputs.property("seedtag.cross", cross)
    systemProperty("seedtag.cross", cross.get())
}
