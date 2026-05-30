// Top-level build file where you can add configuration options common to all sub-projects/modules.
import java.util.Properties

plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

localProperties.getProperty("kunbox.buildDir")
    ?.takeIf { it.isNotBlank() }
    ?.let { configuredBuildDir ->
        val externalBuildRoot = File(configuredBuildDir)
        layout.buildDirectory.set(externalBuildRoot.resolve("root"))

        subprojects {
            layout.buildDirectory.set(
                externalBuildRoot.resolve(project.path.removePrefix(":").replace(':', '/').ifBlank { name })
            )
        }
    }
