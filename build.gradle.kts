// Top-level build file. Plugins are declared here (not applied) so every module
// resolves the same versions from gradle/libs.versions.toml.
//
// AGP 9 has built-in Kotlin: do NOT add org.jetbrains.kotlin.android.
// KSP (2.3.1 or newer is required for AGP 9 built-in Kotlin) runs Room's compiler.
// The Compose compiler plugin below is pinned to the same Kotlin version that
// AGP 9.2 itself depends on, so no Kotlin override is needed.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
