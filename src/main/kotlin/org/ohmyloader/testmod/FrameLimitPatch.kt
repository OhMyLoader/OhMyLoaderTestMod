package org.ohmyloader.testmod

import org.ohmyloader.api.mixin.CallbackInfo
import org.ohmyloader.api.mixin.Unique

/**
 * A member merged into `Minecraft` by [TestRules]. This class is **never loaded** — the loader only
 * reads its bytecode: it references a game class (it is meant to live inside one), and the rules must
 * be in place before the game class loads, so loading it would drag the game class in too early.
 *
 * After the merge, [onFrameLimitRead] is an instance method of `Minecraft`, called directly with
 * `INVOKEVIRTUAL` — so `this` is the `Minecraft` instance, and printing the class name doubles as proof
 * that the merge really happened. **No invokedynamic inside the merged section**: Kotlin string
 * templates and lambdas compile to `invokedynamic`, whose MethodHandle constant requires a class-file
 * version the merged-into class is not guaranteed to have (`ClassFormatError` — the whole class fails
 * to load), so string assembly stays in [TestRules], which is **not merged in**.
 */
class FrameLimitPatch {

    /** `@Unique`: merged into the target class as a **new** field (auto-renamed if it collides with
     * an existing member of the same name and descriptor). */
    @Unique
    private var reads = 0

    /**
     * The handler: on merge it is **name-preserved and promoted to public**, because the injection
     * point hardcodes this name.
     *
     * An `INJECT`-form handler ends with a [CallbackInfo] handle — the receiver does not enter the
     * parameter list (after the merge it is the target instance itself), so the parameter list holds
     * only the handle. It is not read here (only a notification, no short-circuit), but the shape
     * must match: the startup self-check verifies each shape and refuses to start on a mismatch.
     */
    fun onFrameLimitRead(@Suppress("UNUSED_PARAMETER") ci: CallbackInfo) {
        reads++
        // only "read its own field + call a static method": string assembly is done in another class
        // (see the class doc)
        if (reads == 1) TestRules.reportMergedForm(this.javaClass.name)
    }
}
