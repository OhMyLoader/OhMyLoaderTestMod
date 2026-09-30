import org.jetbrains.gradle.ext.Gradle
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings

plugins {
    // Not the loader's oml.convention.kotlin-jvm: the projects are independent, and what it
    // borrowed (Kotlin plugin + Java 27 toolchain) is two lines below.
    alias(libs.plugins.kotlinJvm)
    // Applied exactly as an external mod project would: everything resolves from Maven, so a
    // broken publish surface fails here and not on release day.
    id("org.ohmyloader.gradle")
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.4.1"
}

kotlin {
    jvmToolchain(27)
}

// The pin must match the version the loader was published with; a mismatch fails resolution with
// a clear "could not find" instead of silently resolving to whatever is newest.
val omlVersion = providers.gradleProperty("oml_version").getOrElse("0.1.0-SNAPSHOT")

// The version under test. Switch with -Poml.gameVersion=<version>.
val gameVersion = providers.gradleProperty("oml.gameVersion").orElse("26.3").get()

oml {
    minecraftVersion.set(gameVersion)
    apiVersion.set(omlVersion)
    // oml-native resolves from Maven; when absent the plugin logs a skip and OML's zstd codecs
    // fall back to their vanilla implementations.
    extraJvmArgs.addAll(
        listOf(
            // Workaround for an intermittent 0xC0000005 right after window creation on the Zulu 27 line.
            "-XX:TieredStopAtLevel=1",
        )
    )
}

idea {
    project {
        settings {
            runConfigurations {
                register("Run Client", Gradle::class.java) {
                    taskNames = listOf("runClient")
                }
                register("Run Server", Gradle::class.java) {
                    taskNames = listOf("runServer")
                }
            }
        }
    }
}

// -Poml.quickPlay=<world> / -Poml.quickPlayServer=<host:port> launch straight into a world or server.
tasks.named<JavaExec>("runClient") {
    providers.gradleProperty("oml.quickPlay").orNull?.let { world ->
        args("--quickPlaySingleplayer", world)
    }
    providers.gradleProperty("oml.quickPlayServer").orNull?.let { address ->
        args("--quickPlayMultiplayer", address)
    }
}
