plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin); KGP is declared only to
    // pin its version for :app and to provide kotlin("jvm") for
    // :tools:sr-pipeline. https://developer.android.com/build/migrate-to-built-in-kotlin
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
}
