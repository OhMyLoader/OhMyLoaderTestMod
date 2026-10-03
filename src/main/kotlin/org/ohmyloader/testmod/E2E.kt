package org.ohmyloader.testmod

import org.ohmyloader.api.event.Events
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Machine-readable verdict for the end-to-end runs.
 *
 * Every probe used to be a print statement: a regression produced one line in a log nobody reads, and
 * nothing ever looked at the game automatically. Probes now report here, and the run ends with two
 * kinds of line a script can grep:
 *
 * ```
 * [OML-E2E] READY ticks=200 side=server          the session did what the run was supposed to do
 * [OML-E2E] FAILED <check-name> (<detail>)       one per failed or never-reported check
 * [OML-E2E] RESULT PASS|FAIL checks=<n> failures=<m>
 * ```
 *
 * The verdict is emitted from a shutdown hook — the session ends by the server receiving `/stop` or
 * the client quitting, and neither path can be relied on to reach mod code afterwards. A run with
 * failures exits non-zero (`halt(1)`), so the *exit code* is the gate and the RESULT line is the
 * artifact that says why.
 *
 * Declared expectations are the point: a check that must happen and never does is a failure, not a
 * silent skip. Without a declared expectation this file would only be a prettier println.
 *
 * Properties (without any of them the mod behaves exactly as before):
 * - `oml.e2e=1` — treat this run as an E2E run (verdict enforced through the exit code).
 * - `oml.e2e.ticks=N` — after N CLIENT_TICK / SERVER_TICK dispatches [READY] is printed once the
 *   declared expectations have all reported; on the client the game then quits itself.
 * - `oml.e2e.graceSeconds=N` — how long past `ticks` the driver keeps waiting for the expectations
 *   (default 120, wall-clock). The client's resource reload and world load finish seconds after the
 *   first ticks, so ending on the count alone would cut the pack probes off.
 * - `oml.e2e.timeoutSeconds=N` — watchdog (default 300): a session that never finishes fails.
 * - `oml.e2e.expectExtra=<name>` — self-test of the gate: an expectation nothing reports, so a
 *   working gate must fail. Used by CI to prove the gate can go red before trusting it green.
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
            expect(it) // self-test: nothing ever reports this, so the run must fail
        }

        Runtime.getRuntime().addShutdownHook(
            Thread({ if (report() > 0) Runtime.getRuntime().halt(1) }, "oml-e2e-verdict"),
        )

        val timeout = System.getProperty("oml.e2e.timeoutSeconds")?.toIntOrNull() ?: 300
        Thread({
            Thread.sleep(timeout * 1000L)
            // halt(), not exit(): shutdown hooks would run the verdict first and report PASS on a
            // run that never got anywhere, which is exactly the failure this watchdog exists for.
            emit("$PREFIX RESULT FAIL reason=timeout after ${timeout}s")
            Runtime.getRuntime().halt(1)
        }, "oml-e2e-watchdog").apply { isDaemon = true }.start()

        val ticks = System.getProperty("oml.e2e.ticks")?.toIntOrNull() ?: return
        // Both sides register both events: only the current side's tick ever fires, so one counter
        // and one driver serve the client and the dedicated server.
        Events.CLIENT_TICK.register { tick(ticks, graceSeconds) }
        Events.SERVER_TICK.register { tick(ticks, graceSeconds) }
    }

    /**
     * How long past the minimum dispatch count the driver waits for the expectations, in **wall-clock
     * seconds**. Seconds rather than ticks on purpose: CLIENT_TICK is dispatched per frame, so a
     * software-rendered CI runner produces a fraction of the dispatches a real GPU does, and a
     * tick-denominated grace that is generous here would be far too short there.
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
        if (ticksSeen < target) return
        if (missingExpected().isNotEmpty() && elapsedSeconds() < grace) return
        if (!ready.compareAndSet(false, true)) return
        val side = if (isServerSide()) "server" else "client"
        val missing = missingExpected()
        println(
            "$PREFIX READY ticks=$ticksSeen side=$side" +
                if (missing.isEmpty()) "" else " incomplete=${missing.size} (${missing.joinToString(",")})"
        )
        if (side == "client") quitClient()
    }

    private fun elapsedSeconds(): Long = (System.nanoTime() - startedNanos) / 1_000_000_000

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
     * Client-side self-quit: nothing outside the process can press the close button, and on a
     * dedicated server there is no `getInstance()` at all — the harness stops that side with `/stop`
     * once it has seen [READY].
     *
     * `Minecraft` is resolved through [gameLoader], never by plain `Class.forName`: this mod's classes
     * are loaded by the launcher's parent loader (they sit under `org.ohmyloader.`), and the parent
     * carries the game *jar* without the game's *libraries* — defining the class there fails with
     * `NoClassDefFoundError: com/mojang/brigadier/Message`. The probes document the same rule.
     *
     * `stop()` is the graceful path. If it ever fails, the fallback prints the verdict itself and
     * halts: `halt` skips shutdown hooks, which is exactly why the verdict cannot be left to the hook
     * on this path.
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

    /** The game loader, through OMLCore: game libraries live on it and not on this class's loader. */
    private fun gameLoader(): ClassLoader =
        Class.forName("org.ohmyloader.core.OMLCore").getMethod("gameClassLoader").invoke(null) as ClassLoader

    /** The side is asked through OMLCore: the mod compiles against oml-api only, so it is reflective. */
    private fun isServerSide(): Boolean =
        runCatching {
            Class.forName("org.ohmyloader.core.OMLCore").getMethod("isServerSide").invoke(null) as Boolean
        }.getOrElse { false }
}
