import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform")
    kotlin("jvm") version "2.3.10"
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        intellijIdea("2025.1.3")
        testFramework(TestFrameworkType.Platform)
    }
}
kotlin { jvmToolchain(21) }
