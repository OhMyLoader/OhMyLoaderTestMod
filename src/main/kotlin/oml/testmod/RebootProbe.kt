package oml.testmod

import org.ohmyloader.api26_3.OMLGame
import org.ohmyloader.api26_3.OMLReboot
import org.ohmyloader.api26_3.RebootRestore
import org.ohmyloader.api26_3.currentScreen
import org.ohmyloader.api26_3.event.Events
import org.ohmyloader.api26_3.event.OMLSessionEvents

/**
 * The reboot handover gate: what the second session must be able to prove about the first one.
 *
 * <p>The two halves of a soft reboot never share a heap, so almost nothing about the boundary can be
 * asserted by a probe that lives in one of them. What survives is the plain-data snapshot and the
 * supervisor's lease flags — and that turns out to be exactly the contract worth gating on:
 *
 * | check | means |
 * |:--|:--|
 * | `reboot.snapshot_carried` | the retiring session left a readable snapshot behind |
 * | `reboot.save_opened` | this session opened that save itself, with the launch arguments stripped |
 * | `reboot.state_restored` | the player is standing at the recorded place, in the recorded mode |
 * | `reboot.curtain_lifted` | the red transition released the screen when it was done |
 * | `reboot.window_kept` | the window lease is on if and only if this session adopted a handle |
 * | `reboot.surface_released` | the retiring session really closed its GPU surface |
 * | `reboot.join_masked` | the session mask suppressed the reconnect's join dispatch *and* the vanilla announcement |
 * | `reboot.chat_pure` | no `joined/left the game` text ever reached the chat during the handover |
 * | `session.resumed` | [OMLSessionEvents.RESUMED] fired once, when the curtain lifted |
 *
 * <p>Declared only when the supervisor is actually holding a snapshot: a plain first session has no
 * handover to report, and expecting one would make every ordinary run red.
 */
object RebootProbe {

    private var settled = false

    /** Whether this session is the receiving half of a handover; without one there is nothing to report. */
    private var declared = false

    /** Whether the session that retired did so from the main menu, and its check was declared. */
    private var titleDeclared = false
    private var titleSettled = false

    /** Whether this session must report the handover checks (see [OMLTestMod]'s join expectation). */
    val handoverExpected: Boolean get() = declared

    /** What [OMLSessionEvents.RESUMED] delivered, or null while it has not fired. */
    private var resumedOutcome: String? = null

    /** Set when a join/leave announcement reaches the chat — the one thing the mask must prevent. */
    private var sawJoinLeaveChat = false

    fun declare() {
        if (!E2E.isEnabled) return
        if (!RebootRestore.pendingSnapshot()) return
        declared = true
        E2E.expect(
            "reboot.snapshot_carried",
            "reboot.save_opened",
            "reboot.state_restored",
            "reboot.curtain_lifted",
            "reboot.window_kept",
            "reboot.surface_released",
            "reboot.join_masked",
            "reboot.chat_pure",
            "session.resumed",
        )
        // The soft-reboot lifecycle event: one RESUMED replaces the masked join/leave pair.
        OMLSessionEvents.RESUMED.register { event ->
            resumedOutcome = event.outcome
            println("[oml_testmod] OMLSessionEvents.RESUMED: outcome=${event.outcome} summary=${event.summary}")
        }
        // The announcements are swallowed server-side, before any packet exists; this sees what the
        // client would have rendered had one slipped through. The match is on the default language's
        // text — the gate runs en_us, and the translations themselves carry the keys being masked.
        Events.CHAT_RECEIVED.register { event ->
            val text = event.message
            if ("joined the game" in text || "left the game" in text) sawJoinLeaveChat = true
        }
        println("[oml_testmod] reboot handover expected: ${OMLReboot.leaseTrace()}")
    }

    /**
     * The main-menu variant: there is no snapshot (and therefore no join) to report, so the check
     * is declared as soon as this session learns it is one, and reports once the curtain is gone.
     * The whole point is that the path has no world: no save opened, no player placed, no join
     * masked — and the title screen is what the player is left looking at.
     */
    private fun tickTitleReboot() {
        if (!titleDeclared && RebootRestore.titleOnly) {
            titleDeclared = true
            E2E.expect("reboot.title_returned")
            println("[oml_testmod] main-menu reboot detected: outcome=${RebootRestore.outcome}")
        }
        if (!titleDeclared || titleSettled) return
        if (RebootRestore.state != RebootRestore.State.DONE) return
        titleSettled = true
        val client = OMLGame.client
        val screen = client?.currentScreen
        // The Mods screen is a legitimate landing: a reload started from it reopens it by design.
        val atMainMenu = screen is net.minecraft.client.gui.screens.TitleScreen ||
            screen?.javaClass?.simpleName == "OMLModsScreen"
        E2E.check(
            "reboot.title_returned",
            RebootRestore.outcome == "title=1" && !RebootRestore.transitionActive() &&
                client?.level == null && atMainMenu,
            "outcome=${RebootRestore.outcome} screen=${screen?.javaClass?.simpleName ?: "null"} " +
                "world=${if (client?.level != null) "loaded" else "none"} " +
                "transition=${RebootRestore.overlayTrace()}",
        )
        println(
            "[oml_testmod] main-menu reboot: outcome=${RebootRestore.outcome} " +
                "screen=${screen?.javaClass?.simpleName ?: "null"}",
        )
    }

    /**
     * Reports once, when the restore state machine has finished — success or failure.
     *
     * <p>Gated on [declared], not on the state machine: a session that inherited no snapshot still
     * reaches DONE (there was nothing to restore), and reporting the handover checks from it marked
     * a plain run red for a reboot that never happened — which is what a crashed predecessor left
     * behind.
     */
    fun tickClient() {
        if (!E2E.isEnabled) return
        tickTitleReboot()
        if (settled || !declared) return
        if (RebootRestore.state != RebootRestore.State.DONE) return
        settled = true
        val trace = OMLReboot.leaseTrace()
        E2E.check(
            "reboot.snapshot_carried",
            RebootRestore.describe != "none",
            "no snapshot reached this session",
        )
        E2E.check(
            "reboot.save_opened",
            RebootRestore.openedFromSnapshot,
            "the world was not opened from the snapshot ('${RebootRestore.describe}')",
        )
        E2E.check(
            "reboot.state_restored",
            RebootRestore.outcome == "restored=1",
            "${RebootRestore.outcome} for '${RebootRestore.describe}'",
        )
        val screen = OMLGame.client?.currentScreen
        E2E.check(
            "reboot.curtain_lifted",
            !RebootRestore.transitionActive() &&
                (screen == null || screen.javaClass.simpleName == "OMLModsScreen"),
            "the transition is still covering the frame: ${RebootRestore.overlayTrace()} " +
                "(screen=${screen?.javaClass?.simpleName ?: "null"})",
        )
        // The lease switch and the adoption must agree: opting in and getting a fresh window is the
        // failure this gate exists for, and so is adopting while the switch is off.
        E2E.check(
            "reboot.window_kept",
            RebootRestore.leaseAdopted == RebootRestore.leaseEnabled,
            trace,
        )
        E2E.check("reboot.surface_released", RebootRestore.leaseSurfaceClosed, trace)
        // The mask needs both halves of the evidence: a suppressed dispatch proves the event funnel
        // ran and was filtered (not that the hook was simply absent), and a swallowed announcement
        // proves the broadcast path was filtered on the same window.
        E2E.check(
            "reboot.join_masked",
            RebootRestore.maskedJoins >= 1 && RebootRestore.maskedBroadcasts >= 1,
            "join dispatches masked=${RebootRestore.maskedJoins} " +
                "broadcasts swallowed=${RebootRestore.maskedBroadcasts}",
        )
        E2E.check(
            "reboot.chat_pure",
            !sawJoinLeaveChat,
            "a join/leave announcement reached the chat during the handover",
        )
        E2E.check(
            "session.resumed",
            resumedOutcome != null,
            "OMLSessionEvents.RESUMED never fired",
        )
        println(
            "[oml_testmod] reboot handover: $trace outcome=${RebootRestore.outcome} " +
                "resumed=$resumedOutcome joins-masked=${RebootRestore.maskedJoins} " +
                "broadcasts-masked=${RebootRestore.maskedBroadcasts}",
        )
    }
}
