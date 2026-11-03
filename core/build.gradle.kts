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
