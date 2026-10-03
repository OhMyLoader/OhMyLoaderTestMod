package org.ohmyloader.testmod

import org.ohmyloader.api.mixin.*

/**
 * The frame-rate-limit half of the Mixin front-end validation, split onto its own target class.
 *
 * 26.3 moved the limit off `Minecraft`: there is no `getFramerateLimit()` there any more, and
 * `Minecraft.renderFrame` reads the value from
 * `com.mojang.blaze3d.platform.FramerateLimitTracker.getFramerateLimit()I` instead. A `@Mixin` declares
 * its target per class, so following the method across classes means a second mixin rather than a
 * second annotation — the value-modifying family (`@ModifyConstant` / `@ModifyReturnValue` /
 * `@ModifyExpressionValue`) included: their demos used to sit on the `Minecraft` mixin, where they were
 * guaranteed soft misses once the method moved. Every rule here keeps the **shape** it had on
 * `Minecraft`; only the target class changed.
 *
 * All five rules declare `require = 1`: mixin rules are optional by default, so without it a drifted
 * anchor would silently do one thing less — the exact failure this mod exists to catch.
 */
@Mixin(target = "com.mojang.blaze3d.platform.FramerateLimitTracker")
class FramerateLimitMixin {

    private var frameLimitCalls = 0
    private var fpsCalls = 0

    /**
     * `TAIL`: lands before the **last** return of the target method.
     *
     * 26.3's version is a `tableswitch` over the throttle reason and therefore has several returns, so
     * `TAIL` (last return) and `RETURN` (every return) are not interchangeable here. `TAIL` is the one
     * that means "the value the caller will use".
     */
    @Inject(method = "getFramerateLimit", desc = "()I", at = At(At.TAIL), require = 1)
    fun onLimitFramerate(ci: CallbackInfo) {
        frameLimitCalls++
        if (frameLimitCalls == 1) {
            println("[oml_testmod] Mixin: getFramerateLimit TAIL injection in effect")
        }
    }

    /**
     * **Short-circuit with a value** on the live (26.3) location: the handler makes the target method
     * return 60 directly, and only for the first 5 calls so the clamped value is proven to reach the
     * caller without permanently masking the original read path.
     */
    @Inject(method = "getFramerateLimit", desc = "()I", at = At(At.HEAD), cancellable = true, require = 1)
    fun capFramerateLimit(ci: CallbackInfoReturnable<Int>) {
        fpsCalls++
        if (fpsCalls <= 5) {
            if (fpsCalls == 1) {
                println("[oml_testmod] Mixin: short-circuit with value in effect (getFramerateLimit returns 60 directly)")
            }
            ci.setReturnValue(60)
        }
    }

    /**
     * `@ModifyConstant` on a live site: 26.3's `getFramerateLimit()` clamps with two `bipush` constants
     * (30 for the minute-scale idle clamp, 60 for the long-idle one). Identity transform — the demo
     * proves the constant-rewrite pipeline runs, it does not change game behavior.
     */
    @ModifyConstant(method = "getFramerateLimit", desc = "()I", constant = Constant(intValue = 60), require = 1)
    fun onFramerateLimitConstant(value: Int): Int {
        MergeProbe.modifiedConstant("getFramerateLimit/60", value)
        return value
    }

    /** `@ModifyReturnValue` at the default `RETURN` anchor; the handler shape follows the return type. */
    @ModifyReturnValue(method = "getFramerateLimit", desc = "()I", require = 1)
    fun onFramerateLimitReturn(value: Int): Int {
        MergeProbe.modifiedReturnValue("getFramerateLimit", value)
        return value
    }

    /**
     * `@ModifyExpressionValue` on the same constant `@ModifyConstant` uses, so the two stack on one
     * site; both return identically, so stacking changes nothing at runtime.
     */
    @ModifyExpressionValue(
        method = "getFramerateLimit",
        desc = "()I",
        at = At(At.CONSTANT, args = ["intValue=60"]),
        require = 1,
    )
    fun onFramerateLimitExpressionValue(value: Int): Int {
        MergeProbe.modifiedExpressionValue("getFramerateLimit/60", value)
        return value
    }
}
