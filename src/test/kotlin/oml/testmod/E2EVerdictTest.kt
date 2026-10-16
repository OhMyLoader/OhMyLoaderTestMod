package oml.testmod

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The verdict's own guarantees, proven without a game. Each case is a shape a real run can reach:
 * OMLCore catches a throwing mod initializer and boots on, and the expectations are declared after the
 * probes register, so a suite that never registered would otherwise print PASS over nothing.
 */
class E2EVerdictTest {

    @BeforeTest
    fun startFromAnEmptyVerdict() {
        System.setProperty("oml.e2e", "1")
        E2E.resetForTests()
    }

    @Test
    fun `a run that declared nothing cannot pass`() {
        assertEquals(1, E2E.report(), "an empty expectation set has to count as a failure")
    }

    @Test
    fun `an expectation nobody reported is a failure`() {
        E2E.expect("probe.absent")
        assertEquals(1, E2E.report())
    }

    @Test
    fun `a reported expectation is not a failure`() {
        E2E.expect("probe.reported")
        E2E.check("probe.reported", true)
        assertEquals(0, E2E.report())
    }

    @Test
    fun `a recorded failure survives a later success under the same name`() {
        // The broadcast probe is this shape: it records a throw, then a delivery arrives under the same
        // name. Overwriting would erase the throw.
        E2E.expect("probe.twice")
        E2E.check("probe.twice", false, "the drive threw")
        E2E.hit("probe.twice")
        assertEquals(1, E2E.report())
    }
}
