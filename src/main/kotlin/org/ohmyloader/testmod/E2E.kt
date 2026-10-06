package org.ohmyloader.testmod

import org.ohmyloader.api.event.Events
import org.ohmyloader.testmod.E2E.gameLoader
import org.ohmyloader.testmod.E2E.quitClient
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Machine-readable verdict for the end-to-end runs: probes report here, and a script gates on the
 * RESULT line — the exit code is only the secondary signal. The run knobs are the `oml.e2e.*`
 * system properties, documented where each one is read.
 *
 * ```
 * [OML-E2E] READY ticks=200 side=server      the session did what the run was supposed to do
 * [OML-E2E] FAILED <check-name> (<detail>)   one per failed or never-reported check
 * [OML-E2E] RESULT PASS|FAIL checks=<n> failures=<m>
 * ```
 */
object E2E {

    private const val PREFIX = "[OML-E2E]"

    /**
     * The verdict's second door: the game pipes System.out through its log4j redirect, and log4j
     * stops its context from its own shutdown hook — the two hooks race, and a line printed into a
     * stopped context vanishes from the console and the game's log file alike. FileDescriptor.out is
     * the process's real stdout, which no teardown closes.
     */
    private val rawOut by lazy { PrintStream(FileOutputStream(FileDescriptor.out), true) }

    /** Verdict emission: normal stdout for the game's log files, raw stdout so the gate cannot miss it. */
    private fun emit(line: String) {
        println(line)
        rawOut.println(line)
    }

    /**
     * `oml.e2e=1` — treat this run as an E2E run, so the verdict is enforced through the exit code.
     * Unset, every entry point here stays inert and the mod behaves like a normal one.
     */
    private val enabled = System.getProperty("oml.e2e") != null
    private val lock = Any()
    private val expected = LinkedHashSet<String>()
    private val observed = LinkedHashMap<String, Pair<Boolean, String>>()
    private val installed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    /** Declares checks that must be reported; a missing report counts as a failure. */
    fun expect(vararg names: String) {
        if (!enabled) return
        synchronized(lock) { expected += names }
    }

    /** Records one probe's outcome. Printed only when it went wrong, so a green run stays readable. */
    fun check(name: String, ok: Boolean, detail: String = "") {
        synchronized(lock) { observed[name] = ok to detail }
        if (!ok) {
            println("$PREFIX observed failure: $name${if (detail.isEmpty()) "" else " ($detail)"}")
        }
    }

    /** Records a successful observation. */
    fun hit(name: String) = check(name, true)

    /** Whether this run is an E2E run; probes that cost real work should skip themselves otherwise. */
    val isEnabled: Boolean get() = enabled

    /**
     * Starts the watchdog and the shutdown-hook verdict exactly once, and — when `oml.e2e.ticks` is
     * set — the tick driver that prints [READY] and quits the client.
     */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        if (!enabled) return

        System.getProperty("oml.e2e.expectExtra")?.takeIf { it.isNotBlank() }?.let {
            // `oml.e2e.expectExtra=<name>`: an expectation nothing ever reports, so a working gate must
            // go red. CI runs it to prove the gate can fail before trusting it green.
            expect(it)
        }

        Runtime.getRuntime().addShutdownHook(
            // report() only — halt() from inside a shutdown hook deadlocks (the exiting thread holds
            // Shutdown's lock until every hook finishes, so the FAIL path would hang instead of
            // exiting). The scripts gate on the RESULT line; a zero exit code without PASS is red.
            Thread({ report() }, "oml-e2e-verdict"),
        )

        // `oml.e2e.timeoutSeconds` (default 300): a session that never finishes has to fail, not hang the job.
        val timeout = System.getProperty("oml.e2e.timeoutSeconds")?.toIntOrNull() ?: 300
        Thread(
            {
                Thread.sleep(timeout * 1000L)
                // halt(), not exit(): shutdown hooks would run the verdict first and report PASS on a
                // run that never got anywhere, which is exactly the failure this watchdog exists for.
                emit("$PREFIX RESULT FAIL reason=timeout after ${timeout}s")
                Runtime.getRuntime().halt(1)
            },
            "oml-e2e-watchdog",
        ).apply { isDaemon = true }.start()

        // `oml.e2e.ticks=N`: the minimum tick dispatches before [READY]; unset means no driver, only the
        // verdict. [tick] covers what else it waits for.
        val ticks = System.getProperty("oml.e2e.ticks")?.toIntOrNull() ?: return
        // Both sides register both events: only the current side's tick ever fires, so one counter
        // and one driver serve the client and the dedicated server.
        Events.CLIENT_TICK.register { tick(ticks, graceSeconds) }
        Events.SERVER_TICK.register { tick(ticks, graceSeconds) }
    }

    /**
     * `oml.e2e.graceSeconds` (default 120): how long past the minimum dispatch count the driver keeps
     * waiting for the expectations, in **wall-clock seconds**. Seconds rather than ticks on purpose:
     * CLIENT_TICK is dispatched per frame, so a software-rendered CI runner produces a fraction of the
     * dispatches a real GPU does, and a tick-denominated grace that is generous here would be far too
     * short there.
     */
    private val graceSeconds =
        System.getProperty("oml.e2e.graceSeconds")?.toIntOrNull() ?: 120

    private val startedNanos = System.nanoTime()
    private var ticksSeen = 0

    /**
     * [READY] means "the run did what it was supposed to do": the minimum dispatch count has passed AND
     * every declared expectation has been reported. The second half matters on the client, where the
     * resource reload and the world load finish seconds after the first ticks — ending on the count
     * alone would cut the pack probes off and fail a healthy build. The grace window bounds the wait;
     * after it the session ends anyway and the missing checks become the verdict's failures, which is a
     * red gate rather than a hung job.
     */
    private fun tick(target: Int, grace: Int) {
        ticksSeen++
        if (ticksSeen % 600 == 0 && !isServerSide()) printClientState()
        if (ticksSeen < target) return
        if (missingExpected().isNotEmpty() && elapsedSeconds() < grace) return
        if (!ready.compareAndSet(false, true)) return
        val side = if (isServerSide()) "server" else "client"
        val missing = missingExpected()
        println(
            "$PREFIX READY ticks=$ticksSeen side=$side" +
                if (missing.isEmpty()) "" else " incomplete=${missing.size} (${missing.joinToString(",")})",
        )
        if (side == "client") quitClient()
    }

    private fun elapsedSeconds(): Long = (System.nanoTime() - startedNanos) / 1_000_000_000

    /**
     * Where the client sits, sampled every 600 ticks: a join still loading and a join that never
     * started are otherwise indistinguishable, the tick count alone growing the same way for both.
     * Both members are public on 26.3 (`Minecraft.level`, `Gui.screen()`), reached through
     * [gameLoader] per the parent-loader rule; any failure degrades to an "unavailable" line.
     */
    private fun printClientState() {
        runCatching {
            val minecraft =
                gameLoader().loadClass("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null)
            val mcClass = minecraft.javaClass
            val gui = mcClass.getField("gui").get(minecraft)
            val screen = gui.javaClass.getMethod("screen").invoke(gui)
            val world = mcClass.getField("level").get(minecraft)
            println("$PREFIX client-state ticks=$ticksSeen screen=${screen?.javaClass?.simpleName ?: "null"} world=${if (world != null) "loaded" else "none"}")
        }.onFailure {
            println("$PREFIX client-state ticks=$ticksSeen unavailable (${it.javaClass.simpleName}: ${it.message})")
        }
    }

    private fun missingExpected(): List<String> = synchronized(lock) { expected.filter { it !in observed } }

    /** Prints the failure list and the verdict; returns the failure count. */
    private fun report(): Int {
        val missing: List<String>
        val failed: List<String>
        val checks: Int
        synchronized(lock) {
            missing = expected.filter { it !in observed }
            failed = observed.filterValues { !it.first }.keys.toList()
            checks = observed.size
        }
        for (name in missing) emit("$PREFIX FAILED $name (never reported)")
        for (name in failed) {
            val detail = synchronized(lock) { observed[name]?.second }.orEmpty()
            emit("$PREFIX FAILED $name (${detail.ifEmpty { "failed" }})")
        }
        val failures = missing.size + failed.size
        emit("$PREFIX RESULT ${if (failures == 0) "PASS" else "FAIL"} checks=$checks failures=$failures")
        return failures
    }

    /** Verdict + exit, for the paths that must bypass the shutdown hook (see [quitClient]). */
    private fun reportAndHalt() {
        val failures = report()
        Runtime.getRuntime().halt(if (failures == 0) 0 else 1)
    }

    /**
     * Client-side self-quit: nothing outside the process can press the close button, while the
     * dedicated server is stopped by the harness with `/stop` once it has seen [READY].
     *
     * If `stop()` ever fails the fallback prints the verdict and halts itself: `halt` skips shutdown
     * hooks, which is exactly why this path cannot leave the verdict to the hook.
     */
    private fun quitClient() {
        val graceful = runCatching {
            val minecraft = gameLoader().loadClass("net.minecraft.client.Minecraft")
                .getMethod("getInstance").invoke(null)
            minecraft.javaClass.getMethod("stop").invoke(minecraft)
        }
        graceful.onFailure {
            println("$PREFIX graceful quit unavailable (${it.javaClass.name}: ${it.message}); halting after the verdict")
            it.printStackTrace()
            reportAndHalt()
        }
    }

    /**
     * The game loader, asked of OMLCore reflectively (this mod compiles against oml-api only). The
     * game's libraries sit on it and not on this class's loader, which carries the game *jar* alone —
     * a plain `Class.forName("net.minecraft.client.Minecraft")` fails there with
     * `NoClassDefFoundError: com/mojang/brigadier/Message`.
     */
    private fun gameLoader(): ClassLoader =
        Class.forName("org.ohmyloader.core.OMLCore").getMethod("gameClassLoader").invoke(null) as ClassLoader

    /** The side is asked through OMLCore: the mod compiles against oml-api only, so it is reflective. */
    private fun isServerSide(): Boolean =
        runCatching {
            Class.forName("org.ohmyloader.core.OMLCore").getMethod("isServerSide").invoke(null) as Boolean
        }.getOrElse { false }
}
