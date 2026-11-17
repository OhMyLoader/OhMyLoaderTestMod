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

version = "0.1.0"

kotlin {
    jvmToolchain(27)
}

// The verdict's own rules — what counts as a PASS — are testable without a game, and a real boot
// cannot show them failing without already being red for that reason. This is the fast proof.
dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// One home for the pin: gradle.properties. settings.gradle.kts already requires the property, so a
// fallback literal here could only paper over a missing line with a stale version that resolves —
// and scripts/check-oml-version.sh compares it against the loader and plugin checkouts.
val omlVersion = providers.gradleProperty("oml_version").get()

// The version under test. Switch with -Poml.gameVersion=<version>.
val gameVersion = providers.gradleProperty("oml.gameVersion").orElse("26.3").get()

oml {
    minecraftVersion.set(gameVersion)
    apiVersion.set(omlVersion)
    // oml-native resolves from Maven; when absent the plugin logs a skip and OML's zstd codecs
    // fall back to their vanilla implementations.
}

// The multiplayer gate needs a stable offline profile name; the server runs online-mode=false and
// derives the player UUID from this name, so the two processes only agree when it is pinned.
providers.gradleProperty("oml.e2e.username").orNull?.let { oml.extraGameArgs.add("--username=$it") }

// E2E runs (M1 of the loader workspace's development plan): the verification mod's probes end in a verdict, and the run's
// exit code carries it, so a script or CI job can fail on it. `-Poml.e2e.ticks=N` is the whole switch
// — without it the mod behaves exactly as before.
providers.gradleProperty("oml.e2e.ticks").orNull?.let { ticks ->
    val e2e = listOf("-Doml.e2e=1", "-Doml.e2e.ticks=$ticks") +
        listOf("timeoutSeconds", "graceSeconds", "expectExtra", "quickPlay", "multiplayer").mapNotNull { name ->
            providers.gradleProperty("oml.e2e.$name").orNull?.let { "-Doml.e2e.$name=$it" }
        }
    oml.extraJvmArgs.addAll(e2e)
}

// Ask OML to mirror its console output into a file (see OMLCore.installLogFile); a run that uploads
// the log as an artifact does not have to hope the launcher kept stdout anywhere.
providers.gradleProperty("oml.log.file").orNull?.let { oml.extraJvmArgs.add("-Doml.log.file=$it") }

// A manual reboot-timing run: `-Poml.reboot.afterTicks=<n>` reboots the client n ticks after the
// world loads (OMLTestMod reads the property; the supervisor clears it before the next session).
providers.gradleProperty("oml.reboot.afterTicks").orNull?.let {
    oml.extraJvmArgs.add("-Doml.reboot.afterTicks=$it")
}

// Which door the acceptance run goes through: `api` (default), `command` (`/oml reload`), `ui`
// (the pause menu's Mods button) or `title` (the same buttons on the title screen).
providers.gradleProperty("oml.reboot.via").orNull?.let {
    oml.extraJvmArgs.add("-Doml.reboot.via=$it")
}

// Exercises the loader's process-restart fallback without waiting for a native pin to appear.
providers.gradleProperty("oml.canary.forceRefuse").orNull?.let {
    oml.extraJvmArgs.add("-Doml.canary.forceRefuse=$it")
}

// The loader's own phase diagnostics (see Diagnostics): startup scans, rule compilation,
// class-load phases. `-Poml.diagnostics=1` turns them on for a run.
providers.gradleProperty("oml.diagnostics").orNull?.let { oml.diagnostics.set(it) }

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

// -Poml.runtimeJava=<path-to-java[.exe]> runs the *game* on that JVM while the build itself stays on
// the toolchain. It exists because a bug can live in the JVM rather than in the game: Zulu 27.0.1 has
// a Windows stack-probe defect (JDK-8394484) whose only symptom in a long-running client is
// 0xC0000005 with no hs_err — indistinguishable from a native crash in SDL/OpenGL. Swapping only the
// launched process is what makes that one variable instead of a toolchain rebuild.
// `javaLauncher` is cleared as well: it is set from the toolchain convention and would otherwise win
// over `executable`.
providers.gradleProperty("oml.runtimeJava").orNull?.let { runtimeJava ->
    tasks.withType<JavaExec>().configureEach {
        // `executable =` reads as an assignment to a read-only property in the Kotlin DSL here.
        setExecutable(runtimeJava)
        javaLauncher.set(null)
        doFirst { logger.lifecycle("[oml] game JVM: $runtimeJava") }
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
