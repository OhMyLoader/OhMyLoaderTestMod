package oml.testmod

import org.ohmyloader.api26_3.OMLGame
import org.ohmyloader.api26_3.OMLReboot
import org.ohmyloader.api26_3.RebootRestore
import org.ohmyloader.api26_3.currentScreen

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
 *
 * <p>Declared only when the supervisor is actually holding a snapshot: a plain first session has no
 * handover to report, and expecting one would make every ordinary run red.
 */
object RebootProbe {

    private var settled = false

    fun declare() {
        if (!E2E.isEnabled) return
        if (!RebootRestore.pendingSnapshot()) return
        E2E.expect(
            "reboot.snapshot_carried",
            "reboot.save_opened",
            "reboot.state_restored",
            "reboot.curtain_lifted",
            "reboot.window_kept",
            "reboot.surface_released",
        )
        println("[oml_testmod] reboot handover expected: ${OMLReboot.leaseTrace()}")
    }

    /** Reports once, when the restore state machine has finished — success or failure. */
    fun tickClient() {
        if (!E2E.isEnabled || settled) return
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
        E2E.check(
            "reboot.curtain_lifted",
            !RebootRestore.transitionActive() && OMLGame.client?.currentScreen == null,
            "the transition is still covering the frame: ${RebootRestore.overlayTrace()}",
        )
        // The lease switch and the adoption must agree: opting in and getting a fresh window is the
        // failure this gate exists for, and so is adopting while the switch is off.
        E2E.check(
            "reboot.window_kept",
            RebootRestore.leaseAdopted == RebootRestore.leaseEnabled,
            trace,
        )
        E2E.check("reboot.surface_released", RebootRestore.leaseSurfaceClosed, trace)
        println("[oml_testmod] reboot handover: $trace outcome=${RebootRestore.outcome}")
    }
}
