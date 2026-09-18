// Top-level build file. Plugins are resolved here and applied in the modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    // Applied conditionally in :app — see the comment on the `apply` there.
    alias(libs.plugins.google.services) apply false
}
